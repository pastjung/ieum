package com.itda.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.application.port.out.ActionTokenConsumeResult;
import com.itda.user.application.port.out.ActionTokenIssueResult;
import com.itda.user.application.port.out.ActionTokenLookupResult;
import com.itda.user.application.port.out.ActionTokenRecord;
import com.itda.user.application.port.out.ActionTokenStore;
import com.itda.user.application.port.out.ActionTokenVerification;
import com.itda.user.application.port.out.EmailVerificationTokenVerifier;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRepository;
import com.itda.user.domain.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 이메일 인증 완료의 순서와 실패 분류를 저장소 없이 고정한다 (API 명세 3.4.1·3.6.2절).
 *
 * <p>여기서 확인하는 것은 "무엇을 어떤 순서로 하고, 각 실패를 422·400·503 중 무엇으로 접는가"다. Redis 원자성과 실제 조건부 UPDATE는 가짜 구현으로
 * 증명되지 않으므로 실제 Redis·MariaDB 테스트가 맡는다.
 */
class EmailVerificationServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
  private static final long USER_ID = 42L;
  private static final long SNAPSHOT_ROW_VERSION = 7L;
  private static final String SAMPLE_COMPACT_VALUE = "header.payload.signature";
  private static final String JTI = "0b7c3f2a-1d4e-4a6b-8c9d-0e1f2a3b4c5d";
  private static final String TOKEN_HASH = "a".repeat(43);

  private StubTokenVerifier verifier;
  private RecordingActionTokenStore store;
  private RecordingUserRepository users;
  private RecordingTransactionOperations transaction;
  private EmailVerificationService service;

  @BeforeEach
  void setUp() {
    this.verifier = new StubTokenVerifier();
    this.store = new RecordingActionTokenStore();
    this.users = new RecordingUserRepository();
    this.transaction = new RecordingTransactionOperations();
    this.service =
        new EmailVerificationService(
            this.verifier,
            this.store,
            this.users,
            this.transaction,
            Clock.fixed(NOW, ZoneOffset.UTC));

    this.verifier.willReturn(verified(AccountActionPurpose.EMAIL_VERIFICATION));
    this.users.register(activeUser(SNAPSHOT_ROW_VERSION, false));
    this.users.willUpdate(1);
  }

  private static ActionTokenVerification.Verified verified(AccountActionPurpose purpose) {
    return new ActionTokenVerification.Verified(
        USER_ID, purpose, JTI, TOKEN_HASH, NOW.getEpochSecond(), NOW.getEpochSecond() + 86_400);
  }

  private static User activeUser(long rowVersion, boolean emailVerified) {
    LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    User user =
        User.create("learner01", "argon2id$hash", UserRole.LEARNER, Email.of("a@example.com"), now);
    if (emailVerified) {
      user.verifyEmail(now);
    }
    assign(user, "id", USER_ID);
    assign(user, "rowVersion", rowVersion);
    return user;
  }

  private static void assign(User user, String fieldName, Object value) {
    try {
      java.lang.reflect.Field field = User.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(user, value);
    } catch (ReflectiveOperationException e) {
      // 엔티티에 setter를 만들지 않는다는 규칙을 테스트 편의로 깨지 않으려고 리플렉션을 쓴다.
      throw new IllegalStateException("테스트 fixture를 만들지 못했습니다.", e);
    }
  }

  private ErrorCode errorCode() {
    return catchThrowableOfType(
            BusinessException.class, () -> this.service.complete(SAMPLE_COMPACT_VALUE))
        .getErrorCode();
  }

  // ---------- 성공 ----------

  @Test
  void 적격_토큰을_소비하고_인증을_완료한다() {
    assertThatCode(() -> this.service.complete(SAMPLE_COMPACT_VALUE)).doesNotThrowAnyException();

    assertThat(this.store.consumed).singleElement();
    assertThat(this.users.updates)
        .singleElement()
        .isEqualTo(new Update(USER_ID, SNAPSHOT_ROW_VERSION));
  }

  @Test
  void Redis_소비를_사용자_상태_확인_뒤에_한다() {
    this.service.complete(SAMPLE_COMPACT_VALUE);

    // 소비 시점에 이미 검증과 조회가 끝나 있어야 한다. 순서가 뒤집히면 부적격 토큰이 먼저 소진된다.
    assertThat(this.store.verifyCallCountWhenConsumed).isEqualTo(1);
    assertThat(this.store.findByIdCallCountWhenConsumed).isEqualTo(1);
  }

  @Test
  void MariaDB_갱신을_소비_뒤에_한다() {
    this.service.complete(SAMPLE_COMPACT_VALUE);

    assertThat(this.users.consumeCountWhenUpdated).containsExactly(1);
  }

  @Test
  void 원문_토큰을_가공하지_않고_검증기에_넘긴다() {
    String padded = "  " + SAMPLE_COMPACT_VALUE + "  ";

    this.service.complete(padded);

    // opaque 값이라 trim·정규화·대소문자 변환을 하지 않는다 (3.4.1절).
    assertThat(this.verifier.submitted).containsExactly(padded);
  }

  @Test
  void 소비에_검증된_식별자와_해시를_그대로_쓴다() {
    this.service.complete(SAMPLE_COMPACT_VALUE);

    ActionTokenRecord record = this.store.consumed.get(0);
    assertThat(record.userId()).isEqualTo(USER_ID);
    assertThat(record.purpose()).isEqualTo(AccountActionPurpose.EMAIL_VERIFICATION);
    assertThat(record.jti()).isEqualTo(JTI);
    assertThat(record.tokenHash()).isEqualTo(TOKEN_HASH);
  }

  @Test
  void 사전_lookup을_호출하지_않는다() {
    this.service.complete(SAMPLE_COMPACT_VALUE);

    // 조회와 소비 사이에 상태가 바뀔 수 있어 정확성의 근거가 되지 못한다. 최종 근거는 원자적 소비뿐이다.
    assertThat(this.store.lookupCallCount).isZero();
  }

  // ---------- 형식 위반 (422) ----------

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "   ", "\t\n"})
  void 누락_null_빈값_공백은_422_INVALID_ACCOUNT_INPUT이다(String rawToken) {
    BusinessException thrown =
        catchThrowableOfType(BusinessException.class, () -> this.service.complete(rawToken));

    assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);
    // 형식 위반은 토큰·계정 상태와 무관하다. 검증기까지 가지 않는다.
    assertThat(this.verifier.submitted).isEmpty();
    assertThat(this.store.consumed).isEmpty();
  }

  // ---------- 토큰 부적격 (400) ----------

  @Test
  void 검증_실패는_400_ACTION_TOKEN_INVALID다() {
    this.verifier.willReturn(new ActionTokenVerification.Invalid());

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.store.consumed).isEmpty();
  }

  @Test
  void 다른_용도의_토큰도_400이다() {
    this.verifier.willReturn(verified(AccountActionPurpose.PASSWORD_RESET));

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.store.consumed).isEmpty();
  }

  @Test
  void 사용자가_없으면_400이고_소비하지_않는다() {
    this.users.clear();

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.store.consumed).isEmpty();
  }

  @Test
  void 탈퇴한_사용자도_400이고_소비하지_않는다() {
    User user = activeUser(SNAPSHOT_ROW_VERSION, true);
    user.withdraw(
        "withdrawn-1",
        Email.of("withdrawn-1@example.invalid"),
        "discarded$hash",
        LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    this.users.register(user);

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.store.consumed).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = ActionTokenConsumeResult.class,
      names = {"NOT_FOUND", "NOT_CURRENT", "HASH_MISMATCH", "ALREADY_CONSUMED", "INVALIDATED"})
  void Redis_부적격_결과는_모두_400으로_일반화한다(ActionTokenConsumeResult result) {
    this.store.willReturn(result);

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    // 소비가 성립하지 않았으므로 DB를 건드리지 않는다.
    assertThat(this.users.updates).isEmpty();
  }

  // ---------- 저장소 장애 (503) ----------

  @Test
  void 사용자_조회_장애는_400이_아니라_503이다() {
    this.users.willFail(new QueryTimeoutException("응답 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.store.consumed).isEmpty();
  }

  @Test
  void Redis_결과_불확실이면_503이고_DB를_시작하지_않는다() {
    this.store.willReturn(ActionTokenConsumeResult.UNAVAILABLE);

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    // 실제로 소비됐을 수도, 아닐 수도 있다. 어느 쪽인지 추측해 DB를 바꾸지 않는다 (3.6.2절).
    assertThat(this.users.updates).isEmpty();
    assertThat(this.transaction.executions).isZero();
  }

  @Test
  void 소비_성공_뒤_DB_실패는_204가_아니라_503이다() {
    this.transaction.willFail(new QueryTimeoutException("응답 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    // 토큰을 ACTIVE로 되돌리는 보상을 하지 않는다.
    assertThat(this.store.consumed).hasSize(1);
    assertThat(this.store.restoreCallCount).isZero();
  }

  @Test
  void 소비_성공_뒤_commit_실패도_503이다() {
    this.transaction.willFailAfterAction(new TransactionSystemException("commit 실패"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  void 경합_재조회_장애는_204로_추측하지_않고_503이다() {
    this.users.willUpdate(0);
    this.users.willFailOnSecondRead(new CannotCreateTransactionException("연결 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  // ---------- row_version 경합 ----------

  @Test
  void 조건부_갱신이_0행이면_재조회_후_최신_row_version으로_한_번_재시도한다() {
    this.users.willUpdate(0, 1);
    this.users.registerOnReread(activeUser(SNAPSHOT_ROW_VERSION + 3, false));

    assertThatCode(() -> this.service.complete(SAMPLE_COMPACT_VALUE)).doesNotThrowAnyException();

    assertThat(this.users.updates)
        .containsExactly(
            new Update(USER_ID, SNAPSHOT_ROW_VERSION),
            new Update(USER_ID, SNAPSHOT_ROW_VERSION + 3));
  }

  @Test
  void 두_번째_갱신도_0행이면_503이고_세_번째는_없다() {
    this.users.willUpdate(0, 0);
    this.users.registerOnReread(activeUser(SNAPSHOT_ROW_VERSION + 3, false));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    // 무제한 재시도를 하지 않는다. 토큰은 이미 소진됐다 (3.6.2절).
    assertThat(this.users.updates).hasSize(2);
  }

  @Test
  void 이미_인증된_계정은_덮어쓰지_않고_멱등하게_성공이다() {
    this.users.willUpdate(0);
    this.users.registerOnReread(activeUser(SNAPSHOT_ROW_VERSION + 1, true));

    assertThatCode(() -> this.service.complete(SAMPLE_COMPACT_VALUE)).doesNotThrowAnyException();

    // 두 번째 갱신을 시도하지 않는다 — 기존 인증 시각을 보존해야 한다.
    assertThat(this.users.updates).hasSize(1);
  }

  @Test
  void 재조회에서_사용자가_사라졌으면_400이다() {
    this.users.willUpdate(0);
    this.users.clearOnReread();

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
  }

  @Test
  void 재조회에서_비ACTIVE면_400이다() {
    this.users.willUpdate(0);
    User withdrawn = activeUser(SNAPSHOT_ROW_VERSION + 1, true);
    withdrawn.withdraw(
        "withdrawn-1",
        Email.of("withdrawn-1@example.invalid"),
        "discarded$hash",
        LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    this.users.registerOnReread(withdrawn);

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
  }

  @Test
  void 적격하지_않은_토큰은_계정이_이미_인증됐어도_204가_아니다() {
    this.store.willReturn(ActionTokenConsumeResult.ALREADY_CONSUMED);
    this.users.register(activeUser(SNAPSHOT_ROW_VERSION, true));

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
  }

  // ---------- 노출 ----------

  @Test
  void 오류_message에_토큰도_식별자도_없다() {
    this.verifier.willReturn(new ActionTokenVerification.Invalid());

    BusinessException thrown =
        catchThrowableOfType(
            BusinessException.class, () -> this.service.complete(SAMPLE_COMPACT_VALUE));

    assertThat(thrown.getErrorCode().getMessage())
        .doesNotContain(SAMPLE_COMPACT_VALUE)
        .doesNotContain(JTI)
        .doesNotContain(TOKEN_HASH)
        .doesNotContain(Long.toString(USER_ID));
    assertThat(thrown.getCause()).isNull();
  }

  // ---------- 도우미 ----------

  private record Update(long userId, long expectedRowVersion) {}

  private final class StubTokenVerifier implements EmailVerificationTokenVerifier {

    private final List<String> submitted = new ArrayList<>();
    private ActionTokenVerification result;

    void willReturn(ActionTokenVerification result) {
      this.result = result;
    }

    @Override
    public ActionTokenVerification verify(String compactToken) {
      this.submitted.add(compactToken);
      return this.result;
    }
  }

  private final class RecordingActionTokenStore implements ActionTokenStore {

    private final List<ActionTokenRecord> consumed = new ArrayList<>();
    private ActionTokenConsumeResult result = ActionTokenConsumeResult.CONSUMED;
    private int lookupCallCount;
    private int restoreCallCount;
    private int verifyCallCountWhenConsumed = -1;
    private int findByIdCallCountWhenConsumed = -1;

    void willReturn(ActionTokenConsumeResult result) {
      this.result = result;
    }

    @Override
    public ActionTokenIssueResult issueActionToken(ActionTokenRecord token) {
      // 재발급은 이 경로에 없다. 호출되면 토큰 복원 보상을 한 것이다.
      this.restoreCallCount++;
      throw new UnsupportedOperationException("완료 경로에서 토큰을 발급하지 않는다.");
    }

    @Override
    public ActionTokenLookupResult lookup(ActionTokenRecord submitted) {
      this.lookupCallCount++;
      return ActionTokenLookupResult.ACTIVE;
    }

    @Override
    public ActionTokenConsumeResult consume(ActionTokenRecord submitted) {
      this.verifyCallCountWhenConsumed =
          EmailVerificationServiceTest.this.verifier.submitted.size();
      this.findByIdCallCountWhenConsumed = EmailVerificationServiceTest.this.users.readCount;
      if (this.result == ActionTokenConsumeResult.CONSUMED) {
        this.consumed.add(submitted);
      }
      return this.result;
    }
  }

  private final class RecordingUserRepository implements UserRepository {

    private final List<Update> updates = new ArrayList<>();
    private final List<Integer> consumeCountWhenUpdated = new ArrayList<>();
    private final List<Integer> updateResults = new ArrayList<>();

    private User user;
    private User rereadUser;
    private boolean rereadRegistered;
    private RuntimeException failure;
    private RuntimeException secondReadFailure;
    private int readCount;

    void register(User user) {
      this.user = user;
    }

    void clear() {
      this.user = null;
    }

    void registerOnReread(User user) {
      this.rereadUser = user;
      this.rereadRegistered = true;
    }

    void clearOnReread() {
      this.rereadUser = null;
      this.rereadRegistered = true;
    }

    void willFail(RuntimeException failure) {
      this.failure = failure;
    }

    void willFailOnSecondRead(RuntimeException failure) {
      this.secondReadFailure = failure;
    }

    void willUpdate(int... results) {
      this.updateResults.clear();
      for (int result : results) {
        this.updateResults.add(result);
      }
    }

    @Override
    public Optional<User> findById(Long id) {
      this.readCount++;
      if (this.failure != null) {
        throw this.failure;
      }
      if (this.readCount > 1) {
        if (this.secondReadFailure != null) {
          throw this.secondReadFailure;
        }
        if (this.rereadRegistered) {
          return Optional.ofNullable(this.rereadUser);
        }
      }
      return Optional.ofNullable(this.user);
    }

    @Override
    public int recordLoginFailure(
        long userId, int maxAttempts, LocalDateTime lockedUntil, LocalDateTime now) {
      throw new UnsupportedOperationException("이메일 인증 경로에서는 쓰지 않는다.");
    }

    @Override
    public int recordLoginSuccess(
        long userId, long expectedRowVersion, long expectedAuthVersion, LocalDateTime now) {
      throw new UnsupportedOperationException("이메일 인증 경로에서는 쓰지 않는다.");
    }

    @Override
    public int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
      this.updates.add(new Update(userId, expectedRowVersion));
      this.consumeCountWhenUpdated.add(EmailVerificationServiceTest.this.store.consumed.size());
      int index = this.updates.size() - 1;
      return index < this.updateResults.size() ? this.updateResults.get(index) : 0;
    }

    @Override
    public User save(User user) {
      throw new UnsupportedOperationException("완료 경로는 조건부 UPDATE만 쓴다.");
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

  /** 트랜잭션 경계를 흉내 낸다. {@code execute}가 반환되기 직전이 commit 자리다. */
  private static final class RecordingTransactionOperations implements TransactionOperations {

    private int executions;
    private RuntimeException failureBeforeAction;
    private RuntimeException failureAfterAction;

    void willFail(RuntimeException failure) {
      this.failureBeforeAction = failure;
    }

    void willFailAfterAction(RuntimeException failure) {
      this.failureAfterAction = failure;
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) {
      this.executions++;
      if (this.failureBeforeAction != null) {
        throw this.failureBeforeAction;
      }
      T result = action.doInTransaction(new SimpleTransactionStatus());
      if (this.failureAfterAction != null) {
        throw this.failureAfterAction;
      }
      return result;
    }
  }
}
