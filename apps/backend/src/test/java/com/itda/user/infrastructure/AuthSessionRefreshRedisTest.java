package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.support.redis.RequiresRealRedis;
import com.itda.user.application.AccessTokenRefreshCommand;
import com.itda.user.application.AccessTokenRefreshService;
import com.itda.user.application.AuthSession;
import com.itda.user.application.AuthSessionRevocationCommand;
import com.itda.user.application.AuthSessionRevocationService;
import com.itda.user.application.port.out.AccessTokenIssuer;
import com.itda.user.application.port.out.IssuedRefreshToken;
import com.itda.user.application.port.out.RefreshFamilyIssueResult;
import com.itda.user.application.port.out.RefreshTokenClaims;
import com.itda.user.application.port.out.RefreshTokenIssuer;
import com.itda.user.application.port.out.RefreshTokenRecord;
import com.itda.user.application.port.out.RefreshTokenStore;
import com.itda.user.application.port.out.RefreshTokenVerification;
import com.itda.user.application.port.out.RefreshTokenVerifier;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRepository;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 갱신·로그아웃 유스케이스를 실제 Redis 8.2.9에 대고 검증한다 (API 명세 3.6.1·3.6.2절).
 *
 * <p>Lua 자체의 원자성·상태 전이는 {@link LuaRefreshTokenStoreRedisTest}가 이미 실측으로 고정하고 있다. 여기서 보는 것은 <b>유스케이스가
 * 그 결과를 어떻게 쓰는가</b>다 — 회전 뒤 저장소가 어떤 모습인지, 같은 토큰으로 동시에 갱신하면 하나만 세션을 받는지, 재사용과 로그아웃이 계열을 실제로 닫는지.
 *
 * <p>MariaDB 쪽만 대역이다. 사용자 상태 판정은 MariaDB 테스트가 맡는다.
 */
@SpringBootTest
@RequiresRealRedis
class AuthSessionRefreshRedisTest {

  private static final AtomicLong USER_ID_SEQUENCE = new AtomicLong(9_200_000_000L);
  private static final long AUTH_VERSION = 5L;

  @Autowired private RefreshTokenStore store;
  @Autowired private RefreshTokenIssuer refreshTokenIssuer;
  @Autowired private RefreshTokenVerifier refreshTokenVerifier;
  @Autowired private AccessTokenIssuer accessTokenIssuer;
  @Autowired private StringRedisTemplate redis;
  @Autowired private AuthRedisKeys keys;

  private long userId;
  private String familyId;
  private AccessTokenRefreshService refreshService;
  private AuthSessionRevocationService revocationService;
  private final List<String> createdKeys = new ArrayList<>();

  @BeforeEach
  void setUp() {
    this.userId = USER_ID_SEQUENCE.incrementAndGet();
    this.familyId = java.util.UUID.randomUUID().toString();
    StubUsers users = new StubUsers(this.userId);
    this.refreshService =
        new AccessTokenRefreshService(
            this.refreshTokenVerifier,
            this.store,
            this.refreshTokenIssuer,
            this.accessTokenIssuer,
            users);
    this.revocationService =
        new AuthSessionRevocationService(this.refreshTokenVerifier, this.store);
    this.createdKeys.add(this.keys.family(this.userId, this.familyId));
    this.createdKeys.add(this.keys.families(this.userId));
  }

  /** 이 테스트가 만든 키만 지운다. 개발자의 로컬 Redis에는 다른 데이터가 있다. */
  @AfterEach
  void deleteOnlyKeysCreatedHere() {
    this.createdKeys.forEach(this.redis::delete);
    this.createdKeys.clear();
  }

  // ---------- 준비 ----------

  /** 실제 발급기로 첫 토큰을 만들고 계열을 등록한다. */
  private String seedFamily() {
    IssuedRefreshToken first =
        this.refreshTokenIssuer.issue(
            new RefreshTokenClaims(this.userId, this.familyId, AUTH_VERSION));
    this.createdKeys.add(this.keys.refreshToken(this.userId, first.jti()));
    RefreshFamilyIssueResult issued =
        this.store.issueFamily(
            new RefreshTokenRecord(
                this.userId,
                this.familyId,
                first.jti(),
                first.tokenHash(),
                first.expiresAtEpochSecond()));
    assertThat(issued).isEqualTo(RefreshFamilyIssueResult.ISSUED);
    return first.compactToken();
  }

  private AuthSession refresh(String compactToken) {
    AuthSession session = this.refreshService.refresh(new AccessTokenRefreshCommand(compactToken));
    this.createdKeys.add(
        this.keys.refreshToken(this.userId, claimsOf(session.refreshToken()).jti()));
    return session;
  }

  private RefreshTokenVerification.Verified claimsOf(String compactToken) {
    return (RefreshTokenVerification.Verified) this.refreshTokenVerifier.verify(compactToken);
  }

  private Map<Object, Object> tokenState(String compactToken) {
    return this.redis
        .opsForHash()
        .entries(this.keys.refreshToken(this.userId, claimsOf(compactToken).jti()));
  }

  private Map<Object, Object> familyState() {
    return this.redis.opsForHash().entries(this.keys.family(this.userId, this.familyId));
  }

  private ErrorCode errorCodeOfRefresh(String compactToken) {
    return catchThrowableOfType(
            BusinessException.class,
            () -> this.refreshService.refresh(new AccessTokenRefreshCommand(compactToken)))
        .getErrorCode();
  }

  private ErrorCode errorCodeOfRevoke(String compactToken) {
    return catchThrowableOfType(
            BusinessException.class,
            () -> this.revocationService.revoke(new AuthSessionRevocationCommand(compactToken)))
        .getErrorCode();
  }

  // ---------- 회전 ----------

  @Test
  void 갱신하면_옛_토큰은_ROTATED_새_토큰은_ACTIVE가_된다() {
    String first = seedFamily();

    AuthSession session = refresh(first);

    assertThat(tokenState(first)).containsEntry("status", "ROTATED");
    assertThat(tokenState(session.refreshToken())).containsEntry("status", "ACTIVE");
  }

  @Test
  void 갱신하면_계열의_현재_토큰이_새_jti로_바뀐다() {
    String first = seedFamily();

    AuthSession session = refresh(first);

    assertThat(familyState())
        .containsEntry("currentJti", claimsOf(session.refreshToken()).jti())
        .containsEntry("status", "ACTIVE");
  }

  @Test
  void 갱신해도_옛_토큰의_상태와_만료는_사라지지_않는다() {
    String first = seedFamily();
    String firstKey = this.keys.refreshToken(this.userId, claimsOf(first).jti());

    refresh(first);

    // 흔적을 지우면 아직 수명이 남은 토큰의 재사용을 판별할 수 없다.
    assertThat(this.redis.getExpire(firstKey, TimeUnit.SECONDS)).isPositive();
    assertThat(tokenState(first)).containsEntry("status", "ROTATED");
  }

  @Test
  void 같은_계열을_유지하고_새_jti를_쓴다() {
    String first = seedFamily();

    AuthSession session = refresh(first);

    RefreshTokenVerification.Verified next = claimsOf(session.refreshToken());
    assertThat(next.familyId()).isEqualTo(this.familyId);
    assertThat(next.jti()).isNotEqualTo(claimsOf(first).jti());
    assertThat(next.authVersion()).isEqualTo(AUTH_VERSION);
  }

  @Test
  void 저장된_값에는_토큰_원문이_없다() {
    String first = seedFamily();

    AuthSession session = refresh(first);

    Map<Object, Object> state = tokenState(session.refreshToken());
    assertThat(state.values()).doesNotContain(session.refreshToken()).doesNotContain(first);
    assertThat((String) state.get("tokenHash"))
        .isEqualTo(CompactTokenHasher.hash(session.refreshToken()))
        .hasSize(43);
  }

  @Test
  void 같은_토큰으로_동시에_갱신하면_정확히_하나만_성공한다() throws Exception {
    String first = seedFamily();
    int attempts = 4;
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Object>> calls =
        IntStream.range(0, attempts)
            .<Callable<Object>>mapToObj(
                i ->
                    () -> {
                      start.await();
                      try {
                        return this.refreshService.refresh(new AccessTokenRefreshCommand(first));
                      } catch (BusinessException e) {
                        return e.getErrorCode();
                      }
                    })
            .toList();

    List<Object> results = runConcurrently(calls, start);

    long succeeded = results.stream().filter(AuthSession.class::isInstance).count();
    assertThat(succeeded).isEqualTo(1);
    // 나머지는 재사용으로 판정돼 계열이 폐기된다.
    assertThat(results.stream().filter(ErrorCode.class::isInstance))
        .allMatch(code -> code == ErrorCode.INVALID_REFRESH_TOKEN);
    results.stream()
        .filter(AuthSession.class::isInstance)
        .map(AuthSession.class::cast)
        .forEach(
            session ->
                this.createdKeys.add(
                    this.keys.refreshToken(this.userId, claimsOf(session.refreshToken()).jti())));
  }

  // ---------- 재사용 ----------

  @Test
  void 회전된_토큰을_다시_제출하면_401이고_계열이_폐기된다() {
    String first = seedFamily();
    refresh(first);

    assertThat(errorCodeOfRefresh(first)).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);

    // 탐지와 폐기가 같은 Lua 실행에서 일어난다.
    assertThat(familyState()).containsEntry("status", "REVOKED");
  }

  @Test
  void 계열이_폐기된_뒤의_갱신은_401이다() {
    String first = seedFamily();
    AuthSession session = refresh(first);
    this.revocationService.revoke(new AuthSessionRevocationCommand(session.refreshToken()));

    assertThat(errorCodeOfRefresh(session.refreshToken()))
        .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
  }

  // ---------- 로그아웃 ----------

  @Test
  void 로그아웃하면_계열이_폐기된다() {
    String first = seedFamily();

    assertThatCode(() -> this.revocationService.revoke(new AuthSessionRevocationCommand(first)))
        .doesNotThrowAnyException();

    assertThat(familyState()).containsEntry("status", "REVOKED");
    // 토큰 상태는 건드리지 않는다. 재사용 판별에 필요한 기록이다.
    assertThat(tokenState(first)).containsEntry("status", "ACTIVE");
  }

  @Test
  void 회전된_이전_토큰으로도_로그아웃하면_계열이_폐기된다() {
    String first = seedFamily();
    refresh(first);

    assertThatCode(() -> this.revocationService.revoke(new AuthSessionRevocationCommand(first)))
        .doesNotThrowAnyException();

    // 로그아웃 경로로 재사용 탐지를 우회할 수 없어야 한다.
    assertThat(familyState()).containsEntry("status", "REVOKED");
  }

  @Test
  void 이미_폐기된_계열의_로그아웃은_멱등하게_성공한다() {
    String first = seedFamily();
    this.revocationService.revoke(new AuthSessionRevocationCommand(first));

    assertThatCode(() -> this.revocationService.revoke(new AuthSessionRevocationCommand(first)))
        .doesNotThrowAnyException();

    assertThat(familyState()).containsEntry("status", "REVOKED");
  }

  @Test
  void 계열_key가_사라진_토큰의_로그아웃은_계열을_닫고_401이다() {
    String first = seedFamily();
    this.redis.delete(this.keys.family(this.userId, this.familyId));

    // 계열이 없으면 상태가 결정적으로 어긋난 것이다. fail-closed로 폐기를 시도하되 응답은 401이다.
    assertThat(errorCodeOfRevoke(first)).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
  }

  @Test
  void 등록되지_않은_토큰의_로그아웃은_401이고_계열을_만들지_않는다() {
    IssuedRefreshToken unknown =
        this.refreshTokenIssuer.issue(
            new RefreshTokenClaims(this.userId, this.familyId, AUTH_VERSION));
    this.createdKeys.add(this.keys.refreshToken(this.userId, unknown.jti()));

    assertThat(errorCodeOfRevoke(unknown.compactToken()))
        .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(familyState()).isEmpty();
  }

  // ---------- 도우미 ----------

  private static <T> List<T> runConcurrently(List<Callable<T>> tasks, CountDownLatch start)
      throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
    try {
      List<Future<T>> futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      executor.shutdownNow();
    }
  }

  private static final class StubUsers implements UserRepository {

    private final long userId;
    private final User user;

    private StubUsers(long userId) {
      this.userId = userId;
      LocalDateTime now = LocalDateTime.now();
      this.user =
          User.create(
              "learner01", "$argon2id$stored", UserRole.LEARNER, Email.of("a@example.com"), now);
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
    public Optional<User> findById(Long id) {
      return id == this.userId ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public User save(User user) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<User> findByUsername(String username) {
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

    @Override
    public int recordLoginFailure(
        long userId, int maxAttempts, LocalDateTime lockedUntil, LocalDateTime now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int recordLoginSuccess(
        long userId, long expectedRowVersion, long expectedAuthVersion, LocalDateTime now) {
      throw new UnsupportedOperationException();
    }
  }
}
