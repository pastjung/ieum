package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.support.redis.ActionTokenRedisTestBase;
import com.itda.support.redis.RequiresRealRedis;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.ActionTokenStore;
import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 비밀번호 재설정 완료의 Redis 경로를 실제 Redis 8.2.9에 대고 검증한다 (API 명세 3.4.2·3.6.1·3.6.2절).
 *
 * <p>여기서만 증명되는 것이 있다 — 이미 소비·무효화된 정상 서명 토큰이 실제 사전 조회에서 막혀 유출 조회와 Argon2id에 닿지 않는 것, 정책 실패 뒤 토큰이 실제로
 * {@code ACTIVE}로 남는 것, 동시 제출에서 소비가 한 번만 성공하는 것, 성공 뒤 사용자의 실제 Refresh 계열이 닫히는 것.
 *
 * <p>JWT는 운영과 같은 발급·검증기를 쓴다. 대역은 MariaDB 쪽과 비밀번호 정책의 외부 호출·해시뿐이다.
 */
@SpringBootTest
@RequiresRealRedis
class PasswordResetRedisTest extends ActionTokenRedisTestBase {

  private static final AccountActionPurpose RESET = AccountActionPurpose.PASSWORD_RESET;
  private static final String NEW_PASSWORD = "충분히 긴 새 비밀번호 문구";
  private static final long REFRESH_TTL_SECONDS = 14L * 24 * 60 * 60;

  @Autowired private PasswordResetTokenIssuer tokenIssuer;
  @Autowired private PasswordResetTokenVerifier tokenVerifier;
  @Autowired private RefreshTokenStore refreshTokenStore;

  private StubUserRepository users;
  private CountingBreachChecker checker;
  private CountingPasswordHasher hasher;
  private FailableTransactionOperations transaction;
  private PasswordResetService service;
  private final List<String> issuedJtis = new ArrayList<>();
  private final List<String> refreshKeys = new ArrayList<>();

  @BeforeEach
  void createService() {
    this.users = new StubUserRepository(this.userId);
    this.checker = new CountingBreachChecker();
    this.hasher = new CountingPasswordHasher();
    this.transaction = new FailableTransactionOperations();
    this.service =
        new PasswordResetService(
            this.tokenVerifier,
            this.store,
            this.users,
            new NewPasswordService(this.checker, this.hasher),
            this.refreshTokenStore,
            this.transaction,
            Clock.systemUTC());
  }

  /** 발급기가 만든 jti와 Refresh 키는 base의 정리 대상에 들어가지 않으므로 여기서 지운다. */
  @AfterEach
  void deleteKeysCreatedHere() {
    this.issuedJtis.forEach(
        jti -> this.redis.delete(this.keys.actionToken(this.userId, RESET, jti)));
    this.issuedJtis.clear();
    this.redis.delete(this.refreshKeys);
    this.refreshKeys.clear();
  }

  private IssuedActionToken issueToken() {
    IssuedActionToken token = this.tokenIssuer.issue(new ActionTokenClaims(this.userId));
    this.issuedJtis.add(token.jti());
    return token;
  }

  private IssuedActionToken registeredToken() {
    IssuedActionToken token = issueToken();
    assertThat(this.store.issueActionToken(recordOf(token, token.tokenHash())))
        .isEqualTo(ActionTokenStore.IssueResult.ISSUED);
    return token;
  }

  private ActionTokenStore.TokenRecord recordOf(IssuedActionToken token, String tokenHash) {
    return new ActionTokenStore.TokenRecord(
        this.userId, RESET, token.jti(), tokenHash, token.expiresAtEpochSecond());
  }

  private void complete(IssuedActionToken token, String newPassword) {
    this.service.complete(new PasswordResetService.Command(token.compactToken(), newPassword));
  }

  private ErrorCode errorCodeOf(IssuedActionToken token, String newPassword) {
    return catchThrowableOfType(BusinessException.class, () -> complete(token, newPassword))
        .getErrorCode();
  }

  private ErrorCode errorCodeOf(IssuedActionToken token) {
    return errorCodeOf(token, NEW_PASSWORD);
  }

  private void assertNothingExpensiveRan() {
    assertThat(this.checker.calls()).isZero();
    assertThat(this.hasher.hashes()).isZero();
    assertThat(this.users.updates()).isZero();
  }

  /** 이 사용자의 Refresh 계열을 실제 저장소에 등록한다. */
  private String seedRefreshFamily() {
    String familyId = UUID.randomUUID().toString();
    String jti = UUID.randomUUID().toString();
    this.refreshKeys.add(this.keys.refreshToken(this.userId, jti));
    this.refreshKeys.add(this.keys.family(this.userId, familyId));
    this.refreshKeys.add(this.keys.families(this.userId));
    assertThat(
            this.refreshTokenStore.issueFamily(
                new RefreshTokenStore.TokenRecord(
                    this.userId,
                    familyId,
                    jti,
                    someHash(),
                    Instant.now().getEpochSecond() + REFRESH_TTL_SECONDS)))
        .isEqualTo(RefreshTokenStore.FamilyIssueResult.ISSUED);
    return familyId;
  }

  private Map<Object, Object> refreshFamilyState(String familyId) {
    return this.redis.opsForHash().entries(this.keys.family(this.userId, familyId));
  }

  // ---------- 성공 ----------

  @Test
  void 적격_토큰을_소비하고_action_current를_정리한다() {
    IssuedActionToken token = registeredToken();

    assertThatCode(() -> complete(token, NEW_PASSWORD)).doesNotThrowAnyException();

    assertThat(tokenState(RESET, token.jti())).containsEntry("status", "CONSUMED");
    assertThat(currentJti(RESET)).isNull();
    assertThat(this.users.updates()).isEqualTo(1);
  }

  @Test
  void CONSUMED_상태가_원래_exp까지_유지된다() {
    IssuedActionToken token = registeredToken();

    complete(token, NEW_PASSWORD);

    assertThat(expireTimeOfToken(RESET, token.jti())).isEqualTo(token.expiresAtEpochSecond());
  }

  @Test
  void 성공하면_사용자의_모든_Refresh_계열을_폐기한다() {
    String first = seedRefreshFamily();
    String second = seedRefreshFamily();
    IssuedActionToken token = registeredToken();

    complete(token, NEW_PASSWORD);

    assertThat(refreshFamilyState(first)).containsEntry("status", "REVOKED");
    assertThat(refreshFamilyState(second)).containsEntry("status", "REVOKED");
  }

  // ---------- 사전 조회가 막는 토큰 (400) ----------

  @Test
  void Redis에_없는_토큰은_400이고_유출_조회와_해시를_실행하지_않는다() {
    IssuedActionToken token = issueToken();

    assertThat(errorCodeOf(token)).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertNothingExpensiveRan();
  }

  @Test
  void 재발급으로_무효화된_토큰은_400이고_비싼_계산에_닿지_않는다() {
    IssuedActionToken first = registeredToken();
    registeredToken();

    assertThat(tokenState(RESET, first.jti())).containsEntry("status", "INVALIDATED");
    assertThat(errorCodeOf(first)).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertNothingExpensiveRan();
  }

  @Test
  void 이미_소비한_토큰의_재제출은_400이고_비싼_계산을_반복하지_않는다() {
    IssuedActionToken token = registeredToken();
    complete(token, NEW_PASSWORD);
    int checksAfterFirst = this.checker.calls();
    int hashesAfterFirst = this.hasher.hashes();

    assertThat(errorCodeOf(token)).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);

    assertThat(this.checker.calls()).isEqualTo(checksAfterFirst);
    assertThat(this.hasher.hashes()).isEqualTo(hashesAfterFirst);
    assertThat(this.users.updates()).isEqualTo(1);
  }

  @Test
  void 저장된_해시와_다르면_400이다() {
    IssuedActionToken token = issueToken();
    this.store.issueActionToken(recordOf(token, someHash()));

    assertThat(errorCodeOf(token)).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertNothingExpensiveRan();
  }

  // ---------- 정책 실패는 토큰을 소비하지 않는다 ----------

  @Test
  void 정책_위반이면_토큰이_ACTIVE로_남아_같은_토큰으로_다시_시도할_수_있다() {
    IssuedActionToken token = registeredToken();

    assertThat(errorCodeOf(token, "짧은 비밀번호")).isEqualTo(ErrorCode.INVALID_PASSWORD);
    assertThat(tokenState(RESET, token.jti())).containsEntry("status", "ACTIVE");
    assertThat(currentJti(RESET)).isEqualTo(token.jti());

    assertThatCode(() -> complete(token, NEW_PASSWORD)).doesNotThrowAnyException();
    assertThat(tokenState(RESET, token.jti())).containsEntry("status", "CONSUMED");
  }

  @Test
  void 유출_조회_장애면_토큰이_ACTIVE로_남아_복구_후_같은_토큰으로_성공한다() {
    IssuedActionToken token = registeredToken();
    this.checker.willReturn(CompromisedPasswordChecker.CheckResult.UNAVAILABLE);

    assertThat(errorCodeOf(token)).isEqualTo(ErrorCode.PASSWORD_POLICY_UNAVAILABLE);
    assertThat(tokenState(RESET, token.jti())).containsEntry("status", "ACTIVE");

    this.checker.willReturn(CompromisedPasswordChecker.CheckResult.NOT_COMPROMISED);
    assertThatCode(() -> complete(token, NEW_PASSWORD)).doesNotThrowAnyException();
  }

  // ---------- 동시성 ----------

  @Test
  void 같은_토큰을_동시에_제출해도_비밀번호_변경은_한_번만_성공한다() throws Exception {
    IssuedActionToken token = registeredToken();
    int attempts = 8;
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<ErrorCode>> completions =
        IntStream.range(0, attempts)
            .<Callable<ErrorCode>>mapToObj(
                i ->
                    () -> {
                      start.await();
                      try {
                        complete(token, NEW_PASSWORD);
                        return null;
                      } catch (BusinessException e) {
                        return e.getErrorCode();
                      }
                    })
            .toList();

    List<ErrorCode> results = runConcurrently(completions, start);

    assertThat(results).filteredOn(Objects::isNull).hasSize(1);
    assertThat(results).filteredOn(Objects::nonNull).containsOnly(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.users.updates()).isEqualTo(1);
    assertThat(tokenState(RESET, token.jti())).containsEntry("status", "CONSUMED");
  }

  // ---------- 소비 뒤 DB 실패 ----------

  @Test
  void 소비_성공_뒤_DB가_실패하면_503이고_토큰을_복원하지_않는다() {
    IssuedActionToken token = registeredToken();
    this.transaction.willFail(new QueryTimeoutException("응답 없음"));

    assertThat(errorCodeOf(token)).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(tokenState(RESET, token.jti())).containsEntry("status", "CONSUMED");
    assertThat(currentJti(RESET)).isNull();
  }

  @Test
  void DB_실패_뒤_같은_토큰_재제출은_400이고_계열도_닫지_않는다() {
    String familyId = seedRefreshFamily();
    IssuedActionToken token = registeredToken();
    this.transaction.willFail(new QueryTimeoutException("응답 없음"));
    assertThat(errorCodeOf(token)).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    this.transaction.recover();

    // 정상 계약이다. 사용자는 새 재설정 메일을 요청해 복구한다 (3.7절).
    assertThat(errorCodeOf(token)).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.users.updates()).isZero();
    // DB 변경이 확정되지 않았으므로 사후 정리도 일어나지 않았다.
    assertThat(refreshFamilyState(familyId)).containsEntry("status", "ACTIVE");
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

  /** 여러 스레드가 함께 호출해도 호출 수를 잃지 않는 유출 조회 대역. 외부 네트워크를 쓰지 않는다. */
  private static final class CountingBreachChecker implements CompromisedPasswordChecker {

    private final AtomicInteger calls = new AtomicInteger();
    private volatile CompromisedPasswordChecker.CheckResult result =
        CompromisedPasswordChecker.CheckResult.NOT_COMPROMISED;

    void willReturn(CompromisedPasswordChecker.CheckResult result) {
      this.result = result;
    }

    int calls() {
      return this.calls.get();
    }

    @Override
    public CompromisedPasswordChecker.CheckResult check(String normalizedPassword) {
      this.calls.incrementAndGet();
      return this.result;
    }
  }

  /** Argon2id 비용 없이 해시 호출 수만 센다. */
  private static final class CountingPasswordHasher implements PasswordHasher {

    private final AtomicInteger hashes = new AtomicInteger();

    int hashes() {
      return this.hashes.get();
    }

    @Override
    public PasswordHasher.EncodedPassword hash(String normalizedPassword) {
      this.hashes.incrementAndGet();
      return PasswordHasher.EncodedPassword.of("$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA");
    }

    @Override
    public boolean matches(String normalizedPassword, String encodedHash) {
      throw new UnsupportedOperationException("재설정 경로는 기존 비밀번호와 비교하지 않는다.");
    }
  }

  /** MariaDB 쪽만 대역이다. 조건부 갱신의 실제 동작은 MariaDB 테스트가 검증한다. */
  private static final class StubUserRepository implements UserRepository {

    private final long userId;
    private final AtomicInteger updateCount = new AtomicInteger();
    private final User user;

    private StubUserRepository(long userId) {
      this.userId = userId;
      this.user =
          User.create(
              "learner" + userId,
              "$argon2id$v=19$m=19456,t=2,p=1$old-salt$old-hash",
              UserRole.LEARNER,
              Email.of("a@example.com"),
              LocalDateTime.now());
    }

    int updates() {
      return this.updateCount.get();
    }

    @Override
    public Optional<User> findById(Long id) {
      return id == this.userId ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public int resetPassword(
        long userId,
        long expectedRowVersion,
        String expectedPasswordHash,
        long expectedAuthVersion,
        String newPasswordHash,
        LocalDateTime now) {
      this.updateCount.incrementAndGet();
      return 1;
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
  }

  private static final class FailableTransactionOperations implements TransactionOperations {

    private volatile RuntimeException failure;

    void willFail(RuntimeException failure) {
      this.failure = failure;
    }

    void recover() {
      this.failure = null;
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) {
      if (this.failure != null) {
        throw this.failure;
      }
      return action.doInTransaction(new SimpleTransactionStatus());
    }
  }
}
