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
import com.itda.user.repository.UserJpaRepository;
import com.itda.user.repository.UserRepository;
import com.itda.user.repository.UserRepositoryImpl;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이메일 인증 완료의 MariaDB 조건부 갱신을 운영과 같은 11.8.9에 대고 검증한다 (API 명세 3.6.2절, ERD 3.1절).
 *
 * <p>H2로는 증명되지 않는 것만 여기서 본다 — {@code update versioned}가 실제로 {@code row_version}을 올리는지, 세 조건 ({@code
 * status}·{@code row_version}·{@code email_verified_at is null})이 실제 WHERE로 동작하는지, 경합에서 갱신된 행 수가
 * 어떻게 갈리는지, {@code auth_version}이 그대로인지.
 *
 * <p>Redis는 대역이다. Lua 원자성은 실제 Redis 테스트가 맡고, 여기서는 "소비가 확정된 뒤 DB가 무엇을 하는가"만 본다.
 *
 * <p><b>테스트 트랜잭션을 쓰지 않는다.</b> 조건부 갱신의 commit 결과가 검증 대상이라 각 호출이 자기 트랜잭션을 열어야 한다.
 */
@Tag("mariadb")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(EmailVerificationMariadbTest.RepositoryUnderTest.class)
class EmailVerificationMariadbTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 13, 9, 0);
  private static final String JTI = "0b7c3f2a-1d4e-4a6b-8c9d-0e1f2a3b4c5d";
  private static final String TOKEN_HASH = "a".repeat(43);
  private static final String SAMPLE_COMPACT_VALUE = "header.payload.signature";

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
  private long userId;

  @BeforeEach
  void setUp() {
    this.jdbc = new JdbcTemplate(this.dataSource);
    this.jdbc.execute("delete from mail_outbox");
    this.jdbc.execute("delete from users");
    this.store = new StubActionTokenStore();
    this.userId = insertUser(false);
  }

  private long insertUser(boolean emailVerified) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    User user =
        User.create(
            "learner" + suffix,
            "argon2id$hash",
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

  private EmailVerificationService service() {
    return service(this.userRepository);
  }

  private EmailVerificationService service(UserRepository users) {
    return new EmailVerificationService(
        new StubTokenVerifier(this.userId),
        this.store,
        users,
        new TransactionTemplate(this.transactionManager),
        Clock.fixed(NOW.plusHours(1).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
  }

  /** 조건부 UPDATE는 트랜잭션을 요구한다. 운영 경로에서는 서비스가 열어 준다. */
  private int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
    return new TransactionTemplate(this.transactionManager)
        .execute(status -> this.userRepository.markEmailVerified(userId, expectedRowVersion, now));
  }

  private Map<String, Object> row() {
    return this.jdbc.queryForMap("select * from users where id = ?", this.userId);
  }

  private ErrorCode errorCodeOf(EmailVerificationService service) {
    return catchThrowableOfType(
            BusinessException.class, () -> service.complete(SAMPLE_COMPACT_VALUE))
        .getErrorCode();
  }

  // ---------- 최초 인증 ----------

  @Test
  void 실제_DB가_MariaDB_11_8_9다() {
    assertThat(this.jdbc.queryForObject("select version()", String.class))
        .startsWith("11.8.9-MariaDB");
  }

  @Test
  void 최초_인증은_email_verified_at과_updated_at을_기록하고_row_version을_올린다() {
    Map<String, Object> before = row();
    assertThat(before).containsEntry("email_verified_at", null).containsEntry("row_version", 0L);

    assertThatCode(() -> service().complete(SAMPLE_COMPACT_VALUE)).doesNotThrowAnyException();

    Map<String, Object> after = row();
    assertThat(after.get("email_verified_at")).isNotNull();
    assertThat(after.get("updated_at")).isEqualTo(after.get("email_verified_at"));
    assertThat(after.get("updated_at")).isNotEqualTo(before.get("updated_at"));
    assertThat(after).containsEntry("row_version", 1L);
    // 이메일 인증은 기존 인증 세션을 무효화하는 처리가 아니다 (ERD 3.1절).
    assertThat(after).containsEntry("auth_version", before.get("auth_version"));
    assertThat(after.get("email_verified_at"))
        .isEqualTo(
            this.jdbc.queryForObject(
                "select email_verified_at from users where id = ?",
                java.sql.Timestamp.class,
                this.userId));
  }

  @Test
  void 애플리케이션이_읽는_값도_주입한_UTC_Clock_시각이다() {
    service().complete(SAMPLE_COMPACT_VALUE);

    assertThat(this.userRepository.findById(this.userId).orElseThrow().getEmailVerifiedAt())
        .isEqualTo(NOW.plusHours(1));
  }

  // ---------- 조건부 갱신의 WHERE ----------

  @Test
  void 이미_인증된_행은_0행이라_기존_시각을_덮어쓰지_않는다() {
    service().complete(SAMPLE_COMPACT_VALUE);
    Map<String, Object> afterFirst = row();

    // 같은 조건으로 다시 시도해도 기존 값이 유지된다.
    assertThat(markEmailVerified(this.userId, 1L, NOW.plusDays(1))).isZero();
    assertThat(row().get("email_verified_at")).isEqualTo(afterFirst.get("email_verified_at"));
    assertThat(row()).containsEntry("row_version", 1L);
  }

  @Test
  void row_version이_다르면_0행이다() {
    assertThat(markEmailVerified(this.userId, 99L, NOW)).isZero();
    assertThat(row()).containsEntry("email_verified_at", null);
  }

  @Test
  void ACTIVE가_아니면_0행이다() {
    this.jdbc.update(
        "update users set status = 'WITHDRAWN', withdrawn_at = ? where id = ?", NOW, this.userId);

    assertThat(markEmailVerified(this.userId, 0L, NOW)).isZero();
    assertThat(row()).containsEntry("email_verified_at", null);
  }

  @Test
  void 존재하지_않는_사용자는_0행이다() {
    assertThat(markEmailVerified(this.userId + 987_654L, 0L, NOW)).isZero();
  }

  // ---------- 경합 ----------

  @Test
  void row_version_경합은_재조회_후_한_번_재시도해_성공한다() {
    // 조회와 갱신 사이에 다른 처리가 행을 바꾼 상황을 만든다.
    UserRepository racing = new BumpingUserRepository(this.userRepository, this.jdbc, 1);

    assertThatCode(() -> service(racing).complete(SAMPLE_COMPACT_VALUE)).doesNotThrowAnyException();

    Map<String, Object> after = row();
    assertThat(after.get("email_verified_at")).isNotNull();
    // 경쟁 갱신 1회 + 인증 갱신 1회.
    assertThat(after).containsEntry("row_version", 2L);
  }

  @Test
  void 두_번째_갱신도_어긋나면_503이고_인증은_남지_않는다() {
    // 매 시도 직전에 행이 바뀐다. 무제한 재시도를 하지 않으므로 두 번째에서 끊어야 한다.
    UserRepository alwaysRacing =
        new BumpingUserRepository(this.userRepository, this.jdbc, Integer.MAX_VALUE);

    assertThat(errorCodeOf(service(alwaysRacing))).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(row()).containsEntry("email_verified_at", null);
  }

  @Test
  void 경합_사이에_탈퇴하면_400이다() {
    UserRepository withdrawing =
        new UserRepositoryDecorator(this.userRepository) {
          @Override
          public int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
            EmailVerificationMariadbTest.this.jdbc.update(
                "update users set status = 'WITHDRAWN', withdrawn_at = ?, row_version = row_version"
                    + " + 1 where id = ?",
                NOW,
                userId);
            return super.markEmailVerified(userId, expectedRowVersion, now);
          }
        };

    assertThat(errorCodeOf(service(withdrawing))).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(row()).containsEntry("email_verified_at", null);
  }

  @Test
  void 경합_사이에_이미_인증됐으면_덮어쓰지_않고_성공이다() {
    UserRepository verifyingFirst =
        new UserRepositoryDecorator(this.userRepository) {
          private boolean done;

          @Override
          public int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
            if (!this.done) {
              this.done = true;
              EmailVerificationMariadbTest.this.jdbc.update(
                  "update users set email_verified_at = ?, row_version = row_version + 1 where id ="
                      + " ?",
                  NOW,
                  userId);
            }
            return super.markEmailVerified(userId, expectedRowVersion, now);
          }
        };

    assertThatCode(() -> service(verifyingFirst).complete(SAMPLE_COMPACT_VALUE))
        .doesNotThrowAnyException();

    // 다른 처리가 먼저 기록한 시각이 그대로 남는다 (3.6.2절).
    assertThat(row().get("email_verified_at")).isNotNull();
    assertThat(row()).containsEntry("row_version", 1L);
  }

  // ---------- 소비 뒤 DB 실패 ----------

  @Test
  void 소비_성공_뒤_DB가_실패하면_503이고_인증이_남지_않는다() {
    UserRepository failing =
        new UserRepositoryDecorator(this.userRepository) {
          @Override
          public int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
            super.markEmailVerified(userId, expectedRowVersion, now);
            // 갱신 뒤 같은 트랜잭션에서 실패시킨다. rollback이 확정되면 이번 요청의 변경은 없다.
            throw new org.springframework.dao.QueryTimeoutException("응답 없음");
          }
        };

    assertThat(errorCodeOf(service(failing))).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(row()).containsEntry("email_verified_at", null);
    assertThat(row()).containsEntry("row_version", 0L);
  }

  @Test
  void Redis_결과가_불확실하면_DB를_건드리지_않는다() {
    this.store.willReturn(ActionTokenStore.ConsumeResult.UNAVAILABLE);

    assertThat(errorCodeOf(service())).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(row()).containsEntry("email_verified_at", null);
    assertThat(row()).containsEntry("row_version", 0L);
  }

  // ---------- 도우미 ----------

  /** 매 갱신 직전에 다른 처리가 행을 바꾼 상황을 만든다. */
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
    public int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
      if (this.done < this.bumps) {
        this.done++;
        this.jdbc.update("update users set row_version = row_version + 1 where id = ?", userId);
      }
      return super.markEmailVerified(userId, expectedRowVersion, now);
    }
  }

  private abstract static class UserRepositoryDecorator implements UserRepository {

    private final UserRepository delegate;

    private UserRepositoryDecorator(UserRepository delegate) {
      this.delegate = delegate;
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
  }

  private final class StubTokenVerifier implements EmailVerificationTokenVerifier {

    private final long userId;

    private StubTokenVerifier(long userId) {
      this.userId = userId;
    }

    @Override
    public ActionTokenVerification verify(String compactToken) {
      return new ActionTokenVerification.Verified(
          this.userId,
          AccountActionPurpose.EMAIL_VERIFICATION,
          JTI,
          TOKEN_HASH,
          NOW.toEpochSecond(ZoneOffset.UTC),
          NOW.toEpochSecond(ZoneOffset.UTC) + 86_400);
    }
  }

  private static final class StubActionTokenStore implements ActionTokenStore {

    private ActionTokenStore.ConsumeResult result = ActionTokenStore.ConsumeResult.CONSUMED;

    void willReturn(ActionTokenStore.ConsumeResult result) {
      this.result = result;
    }

    @Override
    public ActionTokenStore.ConsumeResult consume(ActionTokenStore.TokenRecord submitted) {
      return this.result;
    }

    @Override
    public ActionTokenStore.IssueResult issueActionToken(ActionTokenStore.TokenRecord token) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ActionTokenStore.LookupResult lookup(ActionTokenStore.TokenRecord submitted) {
      throw new UnsupportedOperationException();
    }
  }
}
