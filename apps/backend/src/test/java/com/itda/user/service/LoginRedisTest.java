package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.support.redis.RequiresRealRedis;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.AuthRedisKeys;
import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 로그인의 Refresh family 최초 등록을 실제 Redis에 대고 검증한다 (API 명세 3.6.1·3.6.2절).
 *
 * <p>여기서만 증명되는 것이 있다 — 등록된 값이 토큰 원문이 아니라 해시라는 것, 발급기가 만든 해시가 저장된 값과 정확히 같다는 것, 로그인마다 family와 jti가
 * 달라진다는 것, 그리고 등록이 확정되기 전에는 토큰을 돌려주지 않는다는 것.
 *
 * <p>MariaDB 쪽만 대역이다. 조건부 UPDATE의 실제 동작은 MariaDB 테스트가 맡는다.
 */
@SpringBootTest
@RequiresRealRedis
class LoginRedisTest {

  private static final AtomicLong USER_ID_SEQUENCE = new AtomicLong(9_100_000_000L);
  private static final String USERNAME = "learner01";
  private static final String PASSWORD = "충분히 긴 로그인 비밀번호";
  private static final String STORED_HASH = "$argon2id$stored";

  @Autowired private RefreshTokenStore store;
  @Autowired private RefreshTokenIssuer refreshTokenIssuer;
  @Autowired private RefreshTokenVerifier refreshTokenVerifier;
  @Autowired private AccessTokenIssuer accessTokenIssuer;
  @Autowired private StringRedisTemplate redis;
  @Autowired private AuthRedisKeys keys;

  private long userId;
  private StubUserRepository users;
  private LoginService service;
  private final List<String> createdKeys = new ArrayList<>();

  @BeforeEach
  void setUp() {
    this.userId = USER_ID_SEQUENCE.incrementAndGet();
    this.users = new StubUserRepository(this.userId);
    this.service =
        new LoginService(
            this.users,
            new PasswordVerificationService(new FixedPasswordHasher()),
            new DecoyPasswordHash(new FixedPasswordHasher()),
            this.accessTokenIssuer,
            this.refreshTokenIssuer,
            this.store,
            new PassThroughTransactionOperations(),
            Clock.systemUTC());
  }

  /** 이 테스트가 만든 키만 지운다. 개발자의 로컬 Redis에는 다른 데이터가 있다. */
  @AfterEach
  void deleteOnlyKeysCreatedHere() {
    this.createdKeys.forEach(this.redis::delete);
    this.createdKeys.clear();
  }

  private AuthSession login() {
    AuthSession session = this.service.login(new LoginService.Command(USERNAME, PASSWORD));
    RefreshTokenVerifier.Verification verified =
        this.refreshTokenVerifier.verify(session.refreshToken());
    if (verified instanceof RefreshTokenVerifier.Verification.Verified value) {
      this.createdKeys.add(this.keys.refreshToken(this.userId, value.jti()));
      this.createdKeys.add(this.keys.family(this.userId, value.familyId()));
      this.createdKeys.add(this.keys.families(this.userId));
    }
    return session;
  }

  private RefreshTokenVerifier.Verification.Verified claimsOf(AuthSession session) {
    return (RefreshTokenVerifier.Verification.Verified)
        this.refreshTokenVerifier.verify(session.refreshToken());
  }

  private Map<Object, Object> tokenState(RefreshTokenVerifier.Verification.Verified claims) {
    return this.redis.opsForHash().entries(this.keys.refreshToken(this.userId, claims.jti()));
  }

  // ---------- 등록 ----------

  @Test
  void 로그인하면_family가_ISSUED로_등록된다() {
    AuthSession session = login();

    RefreshTokenVerifier.Verification.Verified claims = claimsOf(session);
    assertThat(tokenState(claims)).containsEntry("status", "ACTIVE");
    assertThat(claims.userId()).isEqualTo(this.userId);
  }

  @Test
  void Redis에_저장된_값은_원문이_아니라_해시다() {
    AuthSession session = login();

    RefreshTokenVerifier.Verification.Verified claims = claimsOf(session);
    Map<Object, Object> state = tokenState(claims);

    assertThat(state.values()).doesNotContain(session.refreshToken());
    assertThat(state.values()).doesNotContain(session.accessToken());
    // 계약이 정한 SHA-256 Base64URL 43자.
    assertThat((String) state.get("tokenHash"))
        .isEqualTo(CompactTokenHasher.hash(session.refreshToken()))
        .hasSize(43);
  }

  @Test
  void 키_문자열에도_토큰_원문이_없다() {
    AuthSession session = login();

    RefreshTokenVerifier.Verification.Verified claims = claimsOf(session);

    assertThat(this.keys.refreshToken(this.userId, claims.jti()))
        .doesNotContain(session.refreshToken());
  }

  @Test
  void 로그인마다_다른_family와_jti를_쓴다() {
    AuthSession first = login();
    AuthSession second = login();

    RefreshTokenVerifier.Verification.Verified firstClaims = claimsOf(first);
    RefreshTokenVerifier.Verification.Verified secondClaims = claimsOf(second);

    assertThat(firstClaims.familyId()).isNotEqualTo(secondClaims.familyId());
    assertThat(firstClaims.jti()).isNotEqualTo(secondClaims.jti());
    // 앞선 family를 덮어쓰지 않는다.
    assertThat(tokenState(firstClaims)).containsEntry("status", "ACTIVE");
    assertThat(tokenState(secondClaims)).containsEntry("status", "ACTIVE");
  }

  @Test
  void 발급한_Refresh의_claim이_Snapshot과_일치한다() {
    AuthSession session = login();

    RefreshTokenVerifier.Verification.Verified claims = claimsOf(session);

    assertThat(claims.userId()).isEqualTo(this.userId);
    assertThat(claims.authVersion()).isEqualTo(StubUserRepository.AUTH_VERSION);
  }

  // ---------- 실패 ----------

  @Test
  void Redis를_쓸_수_없으면_503이고_토큰을_돌려주지_않는다() {
    LoginService withBrokenRedis =
        new LoginService(
            this.users,
            new PasswordVerificationService(new FixedPasswordHasher()),
            new DecoyPasswordHash(new FixedPasswordHasher()),
            this.accessTokenIssuer,
            this.refreshTokenIssuer,
            new UnavailableRefreshTokenStore(),
            new PassThroughTransactionOperations(),
            Clock.systemUTC());

    BusinessException thrown =
        catchThrowableOfType(
            BusinessException.class,
            () -> withBrokenRedis.login(new LoginService.Command(USERNAME, PASSWORD)));

    assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    // DB 초기화는 이미 커밋됐지만 되돌리지 않는다. 토큰만 내보내지 않는다 (3.6.2절).
    assertThat(this.users.resets).isEqualTo(1);
  }

  // ---------- 도우미 ----------

  /** 저장된 해시와 정확히 일치할 때만 통과한다. Argon2id 비용 없이 실제 검증 분기를 만든다. */
  private static final class FixedPasswordHasher implements PasswordHasher {

    @Override
    public PasswordHasher.EncodedPassword hash(String normalizedPassword) {
      return PasswordHasher.EncodedPassword.of("$argon2id$decoy$" + normalizedPassword.hashCode());
    }

    @Override
    public boolean matches(String normalizedPassword, String encodedHash) {
      return STORED_HASH.equals(encodedHash) && PASSWORD.equals(normalizedPassword);
    }
  }

  private static final class StubUserRepository implements UserRepository {

    private static final long AUTH_VERSION = 11L;

    private final long userId;
    private final User user;
    private int resets;

    private StubUserRepository(long userId) {
      this.userId = userId;
      LocalDateTime now = LocalDateTime.now();
      this.user =
          User.create(USERNAME, STORED_HASH, UserRole.LEARNER, Email.of("a@example.com"), now);
      this.user.verifyEmail(now);
      assign(this.user, "id", userId);
      assign(this.user, "authVersion", AUTH_VERSION);
    }

    private static void assign(User user, String fieldName, Object value) {
      try {
        java.lang.reflect.Field field = User.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(user, value);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("테스트 fixture를 만들지 못했습니다.", e);
      }
    }

    @Override
    public Optional<User> findByUsername(String username) {
      return USERNAME.equals(username) ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public Optional<User> findById(Long id) {
      return id == this.userId ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public int recordLoginSuccess(
        long userId, long expectedRowVersion, long expectedAuthVersion, LocalDateTime now) {
      this.resets++;
      return 1;
    }

    @Override
    public int recordLoginFailure(
        long userId, int maxAttempts, LocalDateTime lockedUntil, LocalDateTime now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public User save(User user) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<User> findByEmail(Email email) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean existsByUsername(String username) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean existsByEmail(Email email) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class UnavailableRefreshTokenStore
      implements com.itda.user.repository.RefreshTokenStore {

    @Override
    public com.itda.user.repository.RefreshTokenStore.FamilyIssueResult issueFamily(
        com.itda.user.repository.RefreshTokenStore.TokenRecord firstToken) {
      return com.itda.user.repository.RefreshTokenStore.FamilyIssueResult.UNAVAILABLE;
    }

    @Override
    public com.itda.user.repository.RefreshTokenStore.LookupResult lookup(
        com.itda.user.repository.RefreshTokenStore.TokenRecord submitted) {
      throw new UnsupportedOperationException();
    }

    @Override
    public com.itda.user.repository.RefreshTokenStore.RotationResult rotate(
        com.itda.user.repository.RefreshTokenStore.TokenRecord current,
        com.itda.user.repository.RefreshTokenStore.TokenRecord next) {
      throw new UnsupportedOperationException();
    }

    @Override
    public com.itda.user.repository.RefreshTokenStore.FamilyRevocationResult revokeFamily(
        long userId, String familyId) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class PassThroughTransactionOperations implements TransactionOperations {

    @Override
    public <T> T execute(TransactionCallback<T> action) {
      return action.doInTransaction(new SimpleTransactionStatus());
    }
  }
}
