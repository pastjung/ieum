package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserJpaRepository;
import com.itda.user.repository.UserRepository;
import com.itda.user.repository.UserRepositoryImpl;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
 * 갱신의 MariaDB 판정을 운영과 같은 MariaDB 11.8.9에 대고 검증한다 (API 명세 3.3.1·3.6.2절).
 *
 * <p>여기서만 증명되는 것이 있다 — 초기 조회와 회전 후 재확인이 <b>서로 다른 트랜잭션에서 실제로 두 번</b> 일어나는지, 그래서 그 사이에 커밋된 변경을 실제로
 * 보는지. 하나의 트랜잭션으로 묶이면 재확인은 같은 스냅샷을 다시 읽을 뿐이라 아무것도 막지 못한다.
 *
 * <p>회전 사이의 경쟁은 대역 저장소의 {@code rotate} 안에서 DB를 바꿔 만든다. 스레드 타이밍에 기대지 않으므로 결과가 흔들리지 않는다.
 *
 * <p><b>테스트 트랜잭션을 쓰지 않는다.</b> 쓰면 유스케이스의 두 조회가 테스트 트랜잭션 안에 갇혀 중간 변경을 보지 못한다.
 */
@Tag("mariadb")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AuthSessionRefreshMariadbTest.RepositoryUnderTest.class)
class AuthSessionRefreshMariadbTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 9, 0);
  private static final long AUTH_VERSION = 0L;

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
  private long userId;
  private String username;
  private String familyId;
  private StubStore store;
  private AccessTokenRefreshService service;

  @BeforeEach
  void setUp() {
    this.jdbc = new JdbcTemplate(this.dataSource);
    this.jdbc.execute("delete from mail_outbox");
    this.jdbc.execute("delete from users");
    this.username = "learner" + UUID.randomUUID().toString().substring(0, 8);
    this.userId = insertVerifiedUser();
    this.familyId = UUID.randomUUID().toString();
    this.store = new StubStore();
    this.service =
        new AccessTokenRefreshService(
            new StubVerifier(this.userId, this.familyId),
            this.store,
            new StubRefreshIssuer(),
            new StubAccessIssuer(),
            this.userRepository);
  }

  private long insertVerifiedUser() {
    User user =
        User.create(
            this.username,
            "argon2id$hash",
            UserRole.LEARNER,
            Email.of(this.username + "@example.com"),
            NOW);
    user.verifyEmail(NOW);
    return new TransactionTemplate(this.transactionManager)
        .execute(status -> this.userRepository.save(user))
        .getId();
  }

  private void refresh() {
    this.service.refresh(new AccessTokenRefreshService.Command("refresh.compact.value"));
  }

  private ErrorCode errorCode() {
    return catchThrowableOfType(BusinessException.class, this::refresh).getErrorCode();
  }

  // ---------- 환경 ----------

  @Test
  void 실제_DB가_MariaDB_11_8_9다() {
    assertThat(this.jdbc.queryForObject("select version()", String.class))
        .startsWith("11.8.9-MariaDB");
  }

  // ---------- 초기 조회 ----------

  @Test
  void 정상_사용자는_회전하고_성공한다() {
    assertThatCode(this::refresh).doesNotThrowAnyException();

    assertThat(this.store.rotations).isEqualTo(1);
    assertThat(this.store.revokedFamilies).isEmpty();
  }

  @Test
  void 사용자가_없으면_401이고_회전하지_않는다() {
    this.jdbc.update("delete from users where id = ?", this.userId);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.rotations).isZero();
  }

  @Test
  void 탈퇴한_사용자는_401이고_회전하지_않는다() {
    this.jdbc.update(
        "update users set status = 'WITHDRAWN', withdrawn_at = ? where id = ?", NOW, this.userId);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.rotations).isZero();
  }

  @Test
  void authVersion이_다르면_401이고_회전하지_않는다() {
    this.jdbc.update("update users set auth_version = 9 where id = ?", this.userId);

    // 비밀번호 재설정·탈퇴가 끼어든 경우다.
    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.rotations).isZero();
  }

  @Test
  void 이메일_미인증이면_403이고_회전하지_않는다() {
    this.jdbc.update("update users set email_verified_at = null where id = ?", this.userId);

    assertThat(errorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
    assertThat(this.store.rotations).isZero();
  }

  @Test
  void 탈퇴_판정이_이메일_판정보다_먼저다() {
    this.jdbc.update(
        "update users set status = 'WITHDRAWN', withdrawn_at = ?, email_verified_at = null"
            + " where id = ?",
        NOW,
        this.userId);

    // 순서가 바뀌면 탈퇴 계정이 403을 받아 상태가 드러난다.
    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
  }

  // ---------- 회전 후 재확인 ----------

  @Test
  void 회전_뒤_탈퇴가_커밋되면_계열을_폐기하고_401이다() {
    this.store.onRotate(
        () ->
            this.jdbc.update(
                "update users set status = 'WITHDRAWN', withdrawn_at = ? where id = ?",
                NOW,
                this.userId));

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.rotations).isEqualTo(1);
    assertThat(this.store.revokedFamilies).containsExactly(this.familyId);
  }

  @Test
  void 회전_뒤_authVersion이_올라가면_계열을_폐기하고_401이다() {
    this.store.onRotate(
        () -> this.jdbc.update("update users set auth_version = 1 where id = ?", this.userId));

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.revokedFamilies).containsExactly(this.familyId);
  }

  @Test
  void 회전_뒤_사용자가_사라지면_계열을_폐기하고_401이다() {
    this.store.onRotate(() -> this.jdbc.update("delete from users where id = ?", this.userId));

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.revokedFamilies).containsExactly(this.familyId);
  }

  @Test
  void 회전_뒤_이메일_인증만_풀려도_재확인은_통과한다() {
    this.store.onRotate(
        () ->
            this.jdbc.update(
                "update users set email_verified_at = null where id = ?", this.userId));

    // 계약이 재확인 항목으로 정한 것은 status와 auth_version 둘뿐이다.
    assertThatCode(this::refresh).doesNotThrowAnyException();
    assertThat(this.store.revokedFamilies).isEmpty();
  }

  @Test
  void 초기_조회와_재확인은_서로_다른_트랜잭션이다() {
    this.store.onRotate(
        () -> this.jdbc.update("update users set auth_version = 2 where id = ?", this.userId));

    // 두 조회가 한 트랜잭션에 묶였다면 중간 커밋을 보지 못해 성공해 버린다.
    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
  }

  // ---------- 도우미 ----------

  private static final class StubVerifier implements RefreshTokenVerifier {

    private final RefreshTokenVerifier.Verification.Verified verified;

    private StubVerifier(long userId, String familyId) {
      this.verified =
          new RefreshTokenVerifier.Verification.Verified(
              userId,
              familyId,
              UUID.randomUUID().toString(),
              "h".repeat(43),
              AUTH_VERSION,
              1_000L,
              2_000L);
    }

    @Override
    public RefreshTokenVerifier.Verification verify(String compactToken) {
      return this.verified;
    }
  }

  private static final class StubStore implements RefreshTokenStore {

    private final List<String> revokedFamilies = new ArrayList<>();
    private Runnable duringRotation;
    private int rotations;

    void onRotate(Runnable duringRotation) {
      this.duringRotation = duringRotation;
    }

    @Override
    public RefreshTokenStore.LookupResult lookup(RefreshTokenStore.TokenRecord submitted) {
      return RefreshTokenStore.LookupResult.ACTIVE;
    }

    @Override
    public RefreshTokenStore.FamilyIssueResult issueFamily(
        RefreshTokenStore.TokenRecord firstToken) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RefreshTokenStore.RotationResult rotate(
        RefreshTokenStore.TokenRecord current, RefreshTokenStore.TokenRecord next) {
      this.rotations++;
      if (this.duringRotation != null) {
        // 회전이 끝난 직후 다른 트랜잭션이 사용자를 바꾼 상황을 만든다.
        this.duringRotation.run();
      }
      return RefreshTokenStore.RotationResult.ROTATED;
    }

    @Override
    public RefreshTokenStore.FamilyRevocationResult revokeFamily(long userId, String familyId) {
      this.revokedFamilies.add(familyId);
      return RefreshTokenStore.FamilyRevocationResult.REVOKED;
    }
  }

  private static final class StubRefreshIssuer implements RefreshTokenIssuer {

    @Override
    public RefreshTokenIssuer.IssuedToken issue(RefreshTokenIssuer.Claims claims) {
      return new RefreshTokenIssuer.IssuedToken(
          "refresh.compact.next",
          "n".repeat(43),
          UUID.randomUUID().toString(),
          claims.familyId(),
          3_000L,
          3_000L + 1_209_600L);
    }
  }

  private static final class StubAccessIssuer implements AccessTokenIssuer {

    @Override
    public AccessTokenIssuer.IssuedToken issue(AccessTokenIssuer.Claims claims) {
      return new AccessTokenIssuer.IssuedToken("access.compact.next", 4_000L, 4_900L);
    }
  }
}
