package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.support.redis.RequiresRealRedis;
import com.itda.user.application.EmailVerificationService;
import com.itda.user.application.port.out.ActionTokenClaims;
import com.itda.user.application.port.out.ActionTokenRecord;
import com.itda.user.application.port.out.EmailVerificationTokenIssuer;
import com.itda.user.application.port.out.EmailVerificationTokenVerifier;
import com.itda.user.application.port.out.IssuedActionToken;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRepository;
import com.itda.user.domain.UserRole;
import java.time.Clock;
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
 * 이메일 인증 완료의 토큰 소비를 실제 Redis에 대고 검증한다 (API 명세 3.4.1·3.6.2절).
 *
 * <p>여기서만 증명되는 것이 있다 — Lua 한 연산의 원자성, 동시 제출에서 정확히 하나만 성공하는 것, {@code CONSUMED}로 전이한 뒤 {@code
 * action-current}가 함께 정리되는 것, 소비 뒤 DB가 실패해도 토큰이 복원되지 않는 것. mock으로는 어느 것도 보이지 않는다.
 *
 * <p>JWT는 운영과 같은 발급·검증기를 쓴다. 대역은 MariaDB 쪽(사용자 조회·조건부 갱신)뿐이며, 그 경로는 실제 MariaDB 테스트가 따로 맡는다.
 */
@SpringBootTest
@RequiresRealRedis
class EmailVerificationRedisTest extends ActionTokenRedisTestBase {

  private static final AccountActionPurpose EMAIL = AccountActionPurpose.EMAIL_VERIFICATION;

  @Autowired private EmailVerificationTokenIssuer tokenIssuer;
  @Autowired private EmailVerificationTokenVerifier tokenVerifier;

  private StubUserRepository users;
  private FailableTransactionOperations transaction;
  private EmailVerificationService service;
  private final List<String> issuedJtis = new ArrayList<>();

  @BeforeEach
  void createService() {
    this.users = new StubUserRepository(this.userId);
    this.transaction = new FailableTransactionOperations();
    this.service =
        new EmailVerificationService(
            this.tokenVerifier, this.store, this.users, this.transaction, Clock.systemUTC());
  }

  /** 발급기가 만든 jti는 base의 정리 대상에 들어가지 않으므로 여기서 지운다. */
  @AfterEach
  void deleteIssuedTokens() {
    this.issuedJtis.forEach(
        jti -> this.redis.delete(this.keys.actionToken(this.userId, EMAIL, jti)));
    this.issuedJtis.clear();
  }

  /** 운영과 같은 경로로 토큰을 만든다. Redis 등록은 호출자가 고른다. */
  private IssuedActionToken issueToken() {
    IssuedActionToken token = this.tokenIssuer.issue(new ActionTokenClaims(this.userId));
    this.issuedJtis.add(token.jti());
    return token;
  }

  private IssuedActionToken registeredToken() {
    IssuedActionToken token = issueToken();
    this.store.issueActionToken(recordOf(token, token.tokenHash()));
    return token;
  }

  private ActionTokenRecord recordOf(IssuedActionToken token, String tokenHash) {
    return new ActionTokenRecord(
        this.userId, EMAIL, token.jti(), tokenHash, token.expiresAtEpochSecond());
  }

  private ErrorCode errorCodeOf(String compactToken) {
    return catchThrowableOfType(BusinessException.class, () -> this.service.complete(compactToken))
        .getErrorCode();
  }

  // ---------- 소비 성공 ----------

  @Test
  void current_ACTIVE_토큰만_소비에_성공한다() {
    IssuedActionToken token = registeredToken();

    assertThatCode(() -> this.service.complete(token.compactToken())).doesNotThrowAnyException();

    assertThat(tokenState(EMAIL, token.jti())).containsEntry("status", "CONSUMED");
    assertThat(this.users.updates()).isEqualTo(1);
  }

  @Test
  void 상태_전이와_action_current_정리가_한_연산에서_끝난다() {
    IssuedActionToken token = registeredToken();
    assertThat(currentJti(EMAIL)).isEqualTo(token.jti());

    this.service.complete(token.compactToken());

    assertThat(tokenState(EMAIL, token.jti())).containsEntry("status", "CONSUMED");
    // 소비한 토큰이 여전히 current면 재사용 판정이 흔들린다.
    assertThat(currentJti(EMAIL)).isNull();
  }

  @Test
  void CONSUMED_상태가_원래_exp까지_유지된다() {
    IssuedActionToken token = registeredToken();

    this.service.complete(token.compactToken());

    // 소비했다고 키를 지우면 같은 토큰의 재제출이 NOT_FOUND가 되어 "이미 썼다"를 구분할 수 없다.
    assertThat(expireTimeOfToken(EMAIL, token.jti())).isEqualTo(token.expiresAtEpochSecond());
  }

  @Test
  void 토큰_원문이_Redis_key에도_value에도_없다() {
    IssuedActionToken token = registeredToken();
    this.service.complete(token.compactToken());

    Map<String, String> state = tokenState(EMAIL, token.jti());

    assertThat(this.keys.actionToken(this.userId, EMAIL, token.jti()))
        .doesNotContain(token.compactToken());
    assertThat(state.values()).doesNotContain(token.compactToken());
    assertThat(state).containsEntry("tokenHash", token.tokenHash());
  }

  // ---------- 결정적 부적격 (400) ----------

  @Test
  void Redis에_없는_토큰은_400이다() {
    // JWT는 유효하지만 등록된 적이 없다 (NOT_FOUND).
    IssuedActionToken token = issueToken();

    assertThat(errorCodeOf(token.compactToken())).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.users.updates()).isZero();
  }

  @Test
  void 재발급으로_밀려난_토큰은_400이다() {
    IssuedActionToken first = registeredToken();
    registeredToken(); // 같은 사용자·용도의 새 토큰이 action-current를 가져간다

    // NOT_CURRENT 또는 INVALIDATED. 외부에서는 구분하지 않는다.
    assertThat(errorCodeOf(first.compactToken())).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.users.updates()).isZero();
  }

  @Test
  void 저장된_해시와_다르면_400이다() {
    IssuedActionToken token = issueToken();
    // 같은 jti를 다른 해시로 등록한다 (HASH_MISMATCH).
    this.store.issueActionToken(recordOf(token, someHash()));

    assertThat(errorCodeOf(token.compactToken())).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.users.updates()).isZero();
  }

  @Test
  void 이미_소비한_토큰의_재제출은_400이다() {
    IssuedActionToken token = registeredToken();
    this.service.complete(token.compactToken());

    assertThat(errorCodeOf(token.compactToken())).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    // 두 번째 요청은 DB를 건드리지 않는다.
    assertThat(this.users.updates()).isEqualTo(1);
  }

  @Test
  void 소비_실패는_계정이_이미_인증됐어도_400이다() {
    IssuedActionToken token = issueToken();
    this.users.markVerified();

    assertThat(errorCodeOf(token.compactToken())).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
  }

  // ---------- 동시성 ----------

  @Test
  void 같은_토큰을_동시에_제출해도_소비는_한_번만_성공한다() throws Exception {
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
                        this.service.complete(token.compactToken());
                        return null;
                      } catch (BusinessException e) {
                        return e.getErrorCode();
                      }
                    })
            .toList();

    List<ErrorCode> results = runConcurrently(completions, start);

    assertThat(results).filteredOn(java.util.Objects::isNull).hasSize(1);
    assertThat(results)
        .filteredOn(java.util.Objects::nonNull)
        .containsOnly(ErrorCode.ACTION_TOKEN_INVALID);
    // 소비가 한 번만 성공했으므로 DB 갱신도 한 번뿐이다.
    assertThat(this.users.updates()).isEqualTo(1);
    assertThat(tokenState(EMAIL, token.jti())).containsEntry("status", "CONSUMED");
  }

  // ---------- 소비 뒤 DB 실패 ----------

  @Test
  void 소비_성공_뒤_DB가_실패하면_503이고_토큰을_복원하지_않는다() {
    IssuedActionToken token = registeredToken();
    this.transaction.willFail(new QueryTimeoutException("응답 없음"));

    assertThat(errorCodeOf(token.compactToken())).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    // 토큰은 소진된 채로 남는다. 재사용 가능한 토큰을 남기는 것보다 fail-closed가 안전하다 (3.6.2절).
    assertThat(tokenState(EMAIL, token.jti())).containsEntry("status", "CONSUMED");
    assertThat(currentJti(EMAIL)).isNull();
  }

  @Test
  void DB_실패_뒤_같은_토큰_재제출은_400이다() {
    IssuedActionToken token = registeredToken();
    this.transaction.willFail(new QueryTimeoutException("응답 없음"));
    assertThat(errorCodeOf(token.compactToken())).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    this.transaction.recover();

    // 정상 계약이다. 사용자는 새 인증 메일을 요청해 복구한다 (3.7절).
    assertThat(errorCodeOf(token.compactToken())).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.users.updates()).isZero();
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

  /** MariaDB 쪽만 대역이다. 조건부 갱신의 실제 동작은 MariaDB 테스트가 검증한다. */
  private static final class StubUserRepository implements UserRepository {

    private final long userId;
    private final AtomicInteger updateCount = new AtomicInteger();
    private User user;

    private StubUserRepository(long userId) {
      this.userId = userId;
      LocalDateTime now = LocalDateTime.now();
      this.user =
          User.create(
              "learner" + userId,
              "argon2id$hash",
              UserRole.LEARNER,
              Email.of("a@example.com"),
              now);
    }

    void markVerified() {
      this.user.verifyEmail(LocalDateTime.now());
    }

    int updates() {
      return this.updateCount.get();
    }

    @Override
    public Optional<User> findById(Long id) {
      return id == this.userId ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
      this.updateCount.incrementAndGet();
      return 1;
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

    private RuntimeException failure;

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
