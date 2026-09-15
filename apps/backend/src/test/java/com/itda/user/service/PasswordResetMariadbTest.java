package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.ActionTokenStore;
import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserJpaRepository;
import com.itda.user.repository.UserRepository;
import com.itda.user.repository.UserRepositoryImpl;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비밀번호 재설정의 MariaDB 조건부 갱신을 운영과 같은 11.8.9에 대고 검증한다 (API 명세 3.4.2·3.6.2절, ERD 3.1절).
 *
 * <p>H2로는 증명되지 않는 것만 여기서 본다 — 한 {@code update versioned} 문장이 {@code password_hash}·{@code
 * auth_version}·{@code failed_login_count}·{@code locked_until}·{@code updated_at}·{@code
 * row_version}을 함께 확정하는지, 다섯 조건이 실제 WHERE로 동작하는지, 경합과 rollback에서 행이 어떻게 남는지.
 *
 * <p>Redis와 비밀번호 정책의 외부 조회·해시는 대역이다. Lua 원자성은 실제 Redis 테스트가 맡는다.
 *
 * <p><b>테스트 트랜잭션을 쓰지 않는다.</b> 조건부 갱신의 commit 결과가 검증 대상이라 각 호출이 자기 트랜잭션을 열어야 한다.
 */
@Tag("mariadb")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(PasswordResetMariadbTest.RepositoryUnderTest.class)
class PasswordResetMariadbTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 9, 0);
  private static final LocalDateTime RESET_AT = NOW.plusHours(1);
  private static final String JTI = "0b7c3f2a-1d4e-4a6b-8c9d-0e1f2a3b4c5d";
  private static final String TOKEN_HASH = "a".repeat(43);
  private static final String SAMPLE_COMPACT_VALUE = "header.payload.signature";
  private static final String NEW_PASSWORD = "충분히 긴 새 비밀번호 문구";
  private static final String OLD_HASH = "$argon2id$v=19$m=19456,t=2,p=1$old-salt$old-hash";

  /** {@link RecordingPasswordHasher}가 돌려주는 고정 인코딩 결과. */
  private static final String NEW_HASH = "$argon2id$v=19$m=19456,t=2,p=1$fake-salt$fake-hash";

  private static final String OTHER_HASH = "$argon2id$v=19$m=19456,t=2,p=1$other-salt$other-hash";

  @TestConfiguration(proxyBeanMethods = false)
  static class RepositoryUnderTest {
    @Bean
    UserRepository userRepository(UserJpaRepository jpaRepository) {
      return new UserRepositoryImpl(jpaRepository);
    }
  }

  @Autowired private UserRepository userRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;

  private JdbcTemplate jdbc;
  private StubActionTokenStore store;
  private RecordingRefreshTokenStore refreshTokens;
  private RecordingPasswordHasher hasher;
  private long userId;

  @BeforeEach
  void setUp() {
    this.jdbc = new JdbcTemplate(this.dataSource);
    this.jdbc.execute("delete from mail_outbox");
    this.jdbc.execute("delete from users");
    this.store = new StubActionTokenStore();
    this.refreshTokens = new RecordingRefreshTokenStore();
    this.hasher = new RecordingPasswordHasher();
    this.userId = insertUser(true);
  }

  private long insertUser(boolean emailVerified) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    User user =
        User.create(
            "learner" + suffix,
            OLD_HASH,
            UserRole.LEARNER,
            Email.of("learner" + suffix + "@example.com"),
            NOW);
    if (emailVerified) {
      user.verifyEmail(NOW);
    }
    return new TransactionTemplate(this.transactionManager)
        .execute(status -> this.userRepository.save(user))
        .getId();
  }

  private PasswordResetService service() {
    return service(this.userRepository);
  }

  private PasswordResetService service(UserRepository users) {
    return new PasswordResetService(
        new StubTokenVerifier(this.userId),
        this.store,
        users,
        new NewPasswordService(new RecordingCompromisedPasswordChecker(), this.hasher),
        this.refreshTokens,
        new TransactionTemplate(this.transactionManager),
        Clock.fixed(RESET_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
  }

  private static void complete(PasswordResetService service) {
    service.complete(new PasswordResetService.Command(SAMPLE_COMPACT_VALUE, NEW_PASSWORD));
  }

  private static ErrorCode errorCodeOf(PasswordResetService service) {
    return catchThrowableOfType(BusinessException.class, () -> complete(service)).getErrorCode();
  }

  /** 조건부 UPDATE는 트랜잭션을 요구한다. 운영 경로에서는 서비스가 열어 준다. */
  private int resetPassword(
      long userId, long expectedRowVersion, String expectedPasswordHash, long expectedAuthVersion) {
    return new TransactionTemplate(this.transactionManager)
        .execute(
            status ->
                this.userRepository.resetPassword(
                    userId,
                    expectedRowVersion,
                    expectedPasswordHash,
                    expectedAuthVersion,
                    NEW_HASH,
                    RESET_AT));
  }

  private Map<String, Object> row() {
    return this.jdbc.queryForMap("select * from users where id = ?", this.userId);
  }

  private User reload() {
    return this.userRepository.findById(this.userId).orElseThrow();
  }

  private void assertUnchanged(Map<String, Object> before) {
    assertThat(row()).isEqualTo(before);
  }

  // ---------- 확정 ----------

  @Test
  void 실제_DB가_MariaDB_11_8_9다() {
    assertThat(this.jdbc.queryForObject("select version()", String.class))
        .startsWith("11.8.9-MariaDB");
  }

  @Test
  void 비밀번호_인증세대_실패횟수_잠금_갱신시각_row_version을_한_번에_확정한다() {
    this.jdbc.update(
        "update users set failed_login_count = 4, locked_until = ? where id = ?",
        NOW.plusMinutes(5),
        this.userId);
    Map<String, Object> before = row();

    assertThatCode(() -> complete(service())).doesNotThrowAnyException();

    Map<String, Object> after = row();
    assertThat(after).containsEntry("password_hash", NEW_HASH);
    assertThat(after).containsEntry("auth_version", (Long) before.get("auth_version") + 1);
    assertThat(after).containsEntry("failed_login_count", 0);
    assertThat(after).containsEntry("locked_until", null);
    assertThat(after).containsEntry("row_version", (Long) before.get("row_version") + 1);
    assertThat(reload().getUpdatedAt()).isEqualTo(RESET_AT);
  }

  @Test
  void 인증_시각과_계정_식별_정보는_바꾸지_않는다() {
    Map<String, Object> before = row();
    LocalDateTime verifiedAt = reload().getEmailVerifiedAt();

    complete(service());

    Map<String, Object> after = row();
    assertThat(verifiedAt).isNotNull();
    assertThat(reload().getEmailVerifiedAt()).isEqualTo(verifiedAt);
    for (String column :
        List.of("email_verified_at", "status", "role", "username", "email", "id")) {
      assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
    }
  }

  @Test
  void 미인증_계정도_재설정되고_email_verified_at은_NULL로_남는다() {
    this.userId = insertUser(false);

    complete(service());

    // 재설정이 이메일 인증을 우회하는 경로가 되면 안 된다 (3.4.2절).
    assertThat(row()).containsEntry("email_verified_at", null);
    assertThat(row()).containsEntry("password_hash", NEW_HASH);
  }

  @Test
  void 성공_뒤_사후_정리를_요청하고_정리가_실패해도_변경은_남는다() {
    this.refreshTokens.result = RefreshTokenStore.AllFamiliesRevocationResult.UNAVAILABLE;

    assertThatCode(() -> complete(service())).doesNotThrowAnyException();

    assertThat(this.refreshTokens.revokedUserIds).containsExactly(this.userId);
    assertThat(row()).containsEntry("password_hash", NEW_HASH);
    assertThat(row()).containsEntry("auth_version", 1L);
  }

  // ---------- 조건부 갱신의 WHERE ----------

  @Test
  void 조건이_모두_맞으면_1행이고_auth_version을_저장값에서_1_올린다() {
    this.jdbc.update("update users set auth_version = 5 where id = ?", this.userId);

    assertThat(resetPassword(this.userId, 0L, OLD_HASH, 5L)).isEqualTo(1);
    assertThat(row()).containsEntry("auth_version", 6L).containsEntry("row_version", 1L);
  }

  @Test
  void row_version이_다르면_0행이다() {
    Map<String, Object> before = row();

    assertThat(resetPassword(this.userId, 99L, OLD_HASH, 0L)).isZero();
    assertUnchanged(before);
  }

  @Test
  void password_hash가_다르면_0행이다() {
    Map<String, Object> before = row();

    assertThat(resetPassword(this.userId, 0L, OTHER_HASH, 0L)).isZero();
    assertUnchanged(before);
  }

  @Test
  void auth_version이_다르면_0행이다() {
    Map<String, Object> before = row();

    assertThat(resetPassword(this.userId, 0L, OLD_HASH, 1L)).isZero();
    assertUnchanged(before);
  }

  @Test
  void ACTIVE가_아니면_0행이다() {
    this.jdbc.update(
        "update users set status = 'WITHDRAWN', withdrawn_at = ? where id = ?", NOW, this.userId);
    Map<String, Object> before = row();

    assertThat(resetPassword(this.userId, 0L, OLD_HASH, 0L)).isZero();
    assertUnchanged(before);
  }

  @Test
  void 존재하지_않는_사용자는_0행이다() {
    assertThat(resetPassword(this.userId + 987_654L, 0L, OLD_HASH, 0L)).isZero();
  }

  // ---------- 경합 ----------

  @Test
  void row_version만_바뀐_경합은_계산한_해시로_한_번_재시도해_성공한다() {
    UserRepository racing = new BumpingUserRepository(this.userRepository, this.jdbc, 1);

    assertThatCode(() -> complete(service(racing))).doesNotThrowAnyException();

    Map<String, Object> after = row();
    assertThat(after).containsEntry("password_hash", NEW_HASH);
    assertThat(after).containsEntry("auth_version", 1L);
    // 경쟁 갱신 1회 + 재설정 갱신 1회.
    assertThat(after).containsEntry("row_version", 2L);
    assertThat(this.hasher.hashCallCount()).isEqualTo(1);
  }

  @Test
  void 지속_경합은_503이고_비밀번호가_바뀌지_않는다() {
    UserRepository alwaysRacing =
        new BumpingUserRepository(this.userRepository, this.jdbc, Integer.MAX_VALUE);

    assertThat(errorCodeOf(service(alwaysRacing))).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(row()).containsEntry("password_hash", OLD_HASH).containsEntry("auth_version", 0L);
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  @Test
  void 경합_사이에_비밀번호가_바뀌면_덮어쓰지_않고_503이다() {
    UserRepository changingPassword =
        new RacingOnceUserRepository(
            this.userRepository,
            () ->
                this.jdbc.update(
                    "update users set password_hash = ?, row_version = row_version + 1 where id = ?",
                    OTHER_HASH,
                    this.userId));

    assertThat(errorCodeOf(service(changingPassword))).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(row()).containsEntry("password_hash", OTHER_HASH).containsEntry("auth_version", 0L);
  }

  @Test
  void 경합_사이에_auth_version이_오르면_덮어쓰지_않고_503이다() {
    UserRepository bumpingAuthVersion =
        new RacingOnceUserRepository(
            this.userRepository,
            () ->
                this.jdbc.update(
                    "update users set auth_version = auth_version + 1, row_version = row_version"
                        + " + 1 where id = ?",
                    this.userId));

    assertThat(errorCodeOf(service(bumpingAuthVersion)))
        .isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(row()).containsEntry("password_hash", OLD_HASH).containsEntry("auth_version", 1L);
  }

  @Test
  void 경합_사이에_탈퇴하면_400이고_비밀번호가_되살아나지_않는다() {
    UserRepository withdrawing =
        new RacingOnceUserRepository(
            this.userRepository,
            () ->
                this.jdbc.update(
                    "update users set status = 'WITHDRAWN', withdrawn_at = ?, row_version ="
                        + " row_version + 1 where id = ?",
                    NOW,
                    this.userId));

    assertThat(errorCodeOf(service(withdrawing))).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);

    assertThat(row()).containsEntry("password_hash", OLD_HASH).containsEntry("status", "WITHDRAWN");
  }

  // ---------- 소비 뒤 DB 실패 ----------

  @Test
  void 소비_성공_뒤_DB가_실패하면_503이고_변경이_rollback된다() {
    Map<String, Object> before = row();
    UserRepository failing =
        new UserRepositoryDecorator(this.userRepository) {
          @Override
          public int resetPassword(
              long userId,
              long expectedRowVersion,
              String expectedPasswordHash,
              long expectedAuthVersion,
              String newPasswordHash,
              LocalDateTime now) {
            super.resetPassword(
                userId,
                expectedRowVersion,
                expectedPasswordHash,
                expectedAuthVersion,
                newPasswordHash,
                now);
            // 갱신 뒤 같은 트랜잭션에서 실패시킨다. rollback이 확정되면 이번 요청의 변경은 없다.
            throw new QueryTimeoutException("응답 없음");
          }
        };

    assertThat(errorCodeOf(service(failing))).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertUnchanged(before);
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  @Test
  void Redis_소비_결과가_불확실하면_DB를_건드리지_않는다() {
    Map<String, Object> before = row();
    this.store.consumeResult = ActionTokenStore.ConsumeResult.UNAVAILABLE;

    assertThat(errorCodeOf(service())).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertUnchanged(before);
  }

  // ---------- 도우미 ----------

  /** 매 갱신 직전에 다른 처리가 행을 바꾼 상황을 만든다. 인증 필드는 그대로 두고 row_version만 올린다. */
  private static final class BumpingUserRepository extends UserRepositoryDecorator {

    private final JdbcTemplate jdbc;
    private final int bumps;
    private int done;

    private BumpingUserRepository(UserRepository delegate, JdbcTemplate jdbc, int bumps) {
      super(delegate);
      this.jdbc = jdbc;
      this.bumps = bumps;
    }

    @Override
    public int resetPassword(
        long userId,
        long expectedRowVersion,
        String expectedPasswordHash,
        long expectedAuthVersion,
        String newPasswordHash,
        LocalDateTime now) {
      if (this.done < this.bumps) {
        this.done++;
        this.jdbc.update("update users set row_version = row_version + 1 where id = ?", userId);
      }
      return super.resetPassword(
          userId,
          expectedRowVersion,
          expectedPasswordHash,
          expectedAuthVersion,
          newPasswordHash,
          now);
    }
  }

  /** 첫 갱신 직전에 한 번만 다른 변경을 끼워 넣는다. */
  private static final class RacingOnceUserRepository extends UserRepositoryDecorator {

    private final Runnable race;
    private boolean done;

    private RacingOnceUserRepository(UserRepository delegate, Runnable race) {
      super(delegate);
      this.race = race;
    }

    @Override
    public int resetPassword(
        long userId,
        long expectedRowVersion,
        String expectedPasswordHash,
        long expectedAuthVersion,
        String newPasswordHash,
        LocalDateTime now) {
      if (!this.done) {
        this.done = true;
        this.race.run();
      }
      return super.resetPassword(
          userId,
          expectedRowVersion,
          expectedPasswordHash,
          expectedAuthVersion,
          newPasswordHash,
          now);
    }
  }

  private abstract static class UserRepositoryDecorator implements UserRepository {

    private final UserRepository delegate;

    private UserRepositoryDecorator(UserRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public int resetPassword(
        long userId,
        long expectedRowVersion,
        String expectedPasswordHash,
        long expectedAuthVersion,
        String newPasswordHash,
        LocalDateTime now) {
      return this.delegate.resetPassword(
          userId,
          expectedRowVersion,
          expectedPasswordHash,
          expectedAuthVersion,
          newPasswordHash,
          now);
    }

    @Override
    public int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
      return this.delegate.markEmailVerified(userId, expectedRowVersion, now);
    }

    @Override
    public int recordLoginFailure(
        long userId, int maxAttempts, LocalDateTime lockedUntil, LocalDateTime now) {
      return this.delegate.recordLoginFailure(userId, maxAttempts, lockedUntil, now);
    }

    @Override
    public int recordLoginSuccess(
        long userId, long expectedRowVersion, long expectedAuthVersion, LocalDateTime now) {
      return this.delegate.recordLoginSuccess(userId, expectedRowVersion, expectedAuthVersion, now);
    }

    @Override
    public Optional<User> findById(Long id) {
      return this.delegate.findById(id);
    }

    @Override
    public User save(User user) {
      return this.delegate.save(user);
    }

    @Override
    public Optional<User> findByUsername(String username) {
      return this.delegate.findByUsername(username);
    }

    @Override
    public Optional<User> findByEmail(Email email) {
      return this.delegate.findByEmail(email);
    }

    @Override
    public boolean existsByUsername(String username) {
      return this.delegate.existsByUsername(username);
    }

    @Override
    public boolean existsByEmail(Email email) {
      return this.delegate.existsByEmail(email);
    }

    @Override
    public int withdraw(
        long userId,
        long expectedRowVersion,
        String expectedPasswordHash,
        long expectedAuthVersion,
        String pseudonymUsername,
        Email pseudonymEmail,
        String discardedPasswordHash,
        LocalDateTime now) {
      return this.delegate.withdraw(
          userId,
          expectedRowVersion,
          expectedPasswordHash,
          expectedAuthVersion,
          pseudonymUsername,
          pseudonymEmail,
          discardedPasswordHash,
          now);
    }
  }

  private static final class StubTokenVerifier implements PasswordResetTokenVerifier {

    private final long userId;

    private StubTokenVerifier(long userId) {
      this.userId = userId;
    }

    @Override
    public ActionTokenVerification verify(String compactToken) {
      return new ActionTokenVerification.Verified(
          this.userId,
          AccountActionPurpose.PASSWORD_RESET,
          JTI,
          TOKEN_HASH,
          NOW.toEpochSecond(ZoneOffset.UTC),
          NOW.toEpochSecond(ZoneOffset.UTC) + 1_800);
    }
  }

  private static final class StubActionTokenStore implements ActionTokenStore {

    private ActionTokenStore.ConsumeResult consumeResult = ActionTokenStore.ConsumeResult.CONSUMED;

    @Override
    public ActionTokenStore.LookupResult lookup(ActionTokenStore.TokenRecord submitted) {
      return ActionTokenStore.LookupResult.ACTIVE;
    }

    @Override
    public ActionTokenStore.ConsumeResult consume(ActionTokenStore.TokenRecord submitted) {
      return this.consumeResult;
    }

    @Override
    public ActionTokenStore.IssueResult issueActionToken(ActionTokenStore.TokenRecord token) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class RecordingRefreshTokenStore implements RefreshTokenStore {

    private final List<Long> revokedUserIds = new ArrayList<>();
    private RefreshTokenStore.AllFamiliesRevocationResult result =
        RefreshTokenStore.AllFamiliesRevocationResult.REVOKED;

    @Override
    public RefreshTokenStore.AllFamiliesRevocationResult revokeAllFamilies(long userId) {
      this.revokedUserIds.add(userId);
      return this.result;
    }

    @Override
    public RefreshTokenStore.LookupResult lookup(RefreshTokenStore.TokenRecord submitted) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RefreshTokenStore.FamilyIssueResult issueFamily(
        RefreshTokenStore.TokenRecord firstToken) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RefreshTokenStore.RotationResult rotate(
        RefreshTokenStore.TokenRecord current, RefreshTokenStore.TokenRecord next) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RefreshTokenStore.FamilyRevocationResult revokeFamily(long userId, String familyId) {
      throw new UnsupportedOperationException();
    }
  }
}
