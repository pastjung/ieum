package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.support.log.CapturingAppender;
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
 * 비밀번호 재설정 완료의 순서와 실패 분류를 저장소 없이 고정한다 (API 명세 3.4.2·3.6.2절).
 *
 * <p>여기서 확인하는 것은 "무엇을 어떤 순서로 하고, 각 실패를 422·400·503 중 무엇으로 접으며, 무엇을 실행하지 않는가"다. Redis 원자성과 실제 조건부
 * UPDATE는 가짜 구현으로 증명되지 않으므로 실제 Redis·MariaDB 테스트가 맡는다.
 */
class PasswordResetServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
  private static final long USER_ID = 42L;
  private static final long SNAPSHOT_ROW_VERSION = 7L;
  private static final long SNAPSHOT_AUTH_VERSION = 3L;
  private static final String OLD_HASH = "$argon2id$v=19$m=19456,t=2,p=1$old-salt$old-hash";

  /** {@link RecordingPasswordHasher}가 돌려주는 고정 인코딩 결과. */
  private static final String NEW_HASH = "$argon2id$v=19$m=19456,t=2,p=1$fake-salt$fake-hash";

  private static final String SAMPLE_COMPACT_VALUE = "header.payload.signature";
  private static final String NEW_PASSWORD = "충분히 긴 새 비밀번호 문구";
  private static final String JTI = "0b7c3f2a-1d4e-4a6b-8c9d-0e1f2a3b4c5d";
  private static final String TOKEN_HASH = "a".repeat(43);

  /** 협력 객체 호출 순서. 순서가 계약이라 개별 호출 여부만으로는 부족하다. */
  private final List<String> calls = new ArrayList<>();

  private StubTokenVerifier verifier;
  private RecordingActionTokenStore actionTokens;
  private RecordingUserRepository users;
  private RecordingRefreshTokenStore refreshTokens;
  private RecordingTransactionOperations transaction;
  private RecordingCompromisedPasswordChecker checker;
  private RecordingPasswordHasher hasher;
  private PasswordResetService service;

  @BeforeEach
  void setUp() {
    this.verifier = new StubTokenVerifier();
    this.actionTokens = new RecordingActionTokenStore();
    this.users = new RecordingUserRepository();
    this.refreshTokens = new RecordingRefreshTokenStore();
    this.transaction = new RecordingTransactionOperations();
    this.checker =
        new RecordingCompromisedPasswordChecker() {
          @Override
          public CompromisedPasswordChecker.CheckResult check(String normalizedPassword) {
            PasswordResetServiceTest.this.calls.add("breachCheck");
            return super.check(normalizedPassword);
          }
        };
    this.hasher =
        new RecordingPasswordHasher() {
          @Override
          public PasswordHasher.EncodedPassword hash(String normalizedPassword) {
            PasswordResetServiceTest.this.calls.add("hash");
            return super.hash(normalizedPassword);
          }
        };
    this.service =
        new PasswordResetService(
            this.verifier,
            this.actionTokens,
            this.users,
            new NewPasswordService(this.checker, this.hasher),
            this.refreshTokens,
            this.transaction,
            Clock.fixed(NOW, ZoneOffset.UTC));

    this.verifier.willReturn(verified(AccountActionPurpose.PASSWORD_RESET));
    this.users.register(user(SNAPSHOT_ROW_VERSION, OLD_HASH, SNAPSHOT_AUTH_VERSION));
    this.users.willUpdate(1);
  }

  private static ActionTokenVerification.Verified verified(AccountActionPurpose purpose) {
    return new ActionTokenVerification.Verified(
        USER_ID, purpose, JTI, TOKEN_HASH, NOW.getEpochSecond(), NOW.getEpochSecond() + 1_800);
  }

  private static User user(long rowVersion, String passwordHash, long authVersion) {
    LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    User user =
        User.create("learner01", passwordHash, UserRole.LEARNER, Email.of("a@example.com"), now);
    assign(user, "id", USER_ID);
    assign(user, "rowVersion", rowVersion);
    assign(user, "authVersion", authVersion);
    return user;
  }

  private static User withdrawnUser(long rowVersion) {
    User user = user(rowVersion, OLD_HASH, SNAPSHOT_AUTH_VERSION);
    user.withdraw(
        "withdrawn-1",
        Email.of("withdrawn-1@example.invalid"),
        "discarded$hash",
        LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
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

  private static PasswordResetService.Command command() {
    return new PasswordResetService.Command(SAMPLE_COMPACT_VALUE, NEW_PASSWORD);
  }

  private ErrorCode errorCodeOf(PasswordResetService.Command command) {
    return catchThrowableOfType(BusinessException.class, () -> this.service.complete(command))
        .getErrorCode();
  }

  private ErrorCode errorCode() {
    return errorCodeOf(command());
  }

  private void assertNothingExpensiveRan() {
    assertThat(this.checker.checkCallCount()).isZero();
    assertThat(this.hasher.hashCallCount()).isZero();
    assertThat(this.actionTokens.consumeAttempts).isEmpty();
  }

  // ---------- 성공 ----------

  @Test
  void 적격_토큰으로_비밀번호를_바꾸고_Refresh_계열을_정리한다() {
    assertThatCode(() -> this.service.complete(command())).doesNotThrowAnyException();

    assertThat(this.actionTokens.consumeAttempts).singleElement();
    assertThat(this.users.updates)
        .singleElement()
        .isEqualTo(
            new Update(USER_ID, SNAPSHOT_ROW_VERSION, OLD_HASH, SNAPSHOT_AUTH_VERSION, NEW_HASH));
    assertThat(this.refreshTokens.revokedUserIds).containsExactly(USER_ID);
  }

  @Test
  void 처리_순서가_계약과_같다() {
    this.service.complete(command());

    // JWT → 사용자 → Redis 사전 조회 → 정책(유출 조회) → Argon2id → 소비 → DB → 사후 정리 (3.4.2·3.6.2절).
    assertThat(this.calls)
        .containsExactly(
            "verify",
            "findById",
            "lookup",
            "breachCheck",
            "hash",
            "consume",
            "resetPassword",
            "revokeAllFamilies");
  }

  @Test
  void 원문_토큰을_가공하지_않고_검증기에_넘긴다() {
    String padded = "  " + SAMPLE_COMPACT_VALUE + "  ";

    this.service.complete(new PasswordResetService.Command(padded, NEW_PASSWORD));

    assertThat(this.verifier.submitted).containsExactly(padded);
  }

  @Test
  void 새_비밀번호의_앞뒤_공백을_지우지_않고_정책과_해시에_넘긴다() {
    String padded = "  " + NEW_PASSWORD + "  ";

    this.service.complete(new PasswordResetService.Command(SAMPLE_COMPACT_VALUE, padded));

    assertThat(this.checker.checkedPasswords()).containsExactly(padded);
    assertThat(this.hasher.hashedPasswords()).containsExactly(padded);
  }

  @Test
  void 사전_조회와_소비에_검증된_식별자와_해시를_그대로_쓴다() {
    this.service.complete(command());

    ActionTokenStore.TokenRecord expected =
        new ActionTokenStore.TokenRecord(
            USER_ID,
            AccountActionPurpose.PASSWORD_RESET,
            JTI,
            TOKEN_HASH,
            NOW.getEpochSecond() + 1_800);
    assertThat(this.actionTokens.lookedUp).containsExactly(expected);
    assertThat(this.actionTokens.consumeAttempts).containsExactly(expected);
  }

  @Test
  void 기존_비밀번호와_같은지_검사하지_않는다() {
    this.service.complete(command());

    // 정책을 통과하면 허용한다. 비교에 Argon2id 검증을 한 번 더 쓰지 않는다 (3.4.2절).
    assertThat(this.hasher.matchesCallCount()).isZero();
    assertThat(this.users.updates).hasSize(1);
  }

  @Test
  void 토큰을_발급하거나_복원하지_않는다() {
    this.service.complete(command());

    assertThat(this.actionTokens.issueCallCount).isZero();
  }

  // ---------- 형식 위반 (422 INVALID_ACCOUNT_INPUT) ----------

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "   ", "\t\n"})
  void token의_누락_null_빈값_공백은_422_INVALID_ACCOUNT_INPUT이다(String rawToken) {
    assertThat(errorCodeOf(new PasswordResetService.Command(rawToken, NEW_PASSWORD)))
        .isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);
    // 형식 위반은 토큰·계정 상태와 무관하다. 검증기까지 가지 않는다.
    assertThat(this.calls).isEmpty();
  }

  @Test
  void 새_비밀번호가_누락_null_비문자열이면_422_INVALID_ACCOUNT_INPUT이다() {
    // DTO가 누락·null·비문자열을 모두 null로 접어 넘긴다.
    assertThat(errorCodeOf(new PasswordResetService.Command(SAMPLE_COMPACT_VALUE, null)))
        .isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);
    assertThat(this.calls).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "   "})
  void 빈_새_비밀번호와_공백_새_비밀번호는_required가_아니라_정책_위반이다(String rawNewPassword) {
    assertThat(errorCodeOf(new PasswordResetService.Command(SAMPLE_COMPACT_VALUE, rawNewPassword)))
        .isEqualTo(ErrorCode.INVALID_PASSWORD);
    // 정책 판정까지 왔지만 토큰은 소비하지 않는다.
    assertThat(this.actionTokens.lookedUp).hasSize(1);
    assertNothingExpensiveRan();
  }

  // ---------- 토큰·사용자 부적격 (400) ----------

  @Test
  void JWT_검증_실패는_400이고_사용자를_조회하지_않는다() {
    this.verifier.willReturn(new ActionTokenVerification.Invalid());

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.calls).containsExactly("verify");
  }

  @Test
  void 이메일_인증_용도의_토큰은_400이다() {
    this.verifier.willReturn(verified(AccountActionPurpose.EMAIL_VERIFICATION));

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.calls).containsExactly("verify");
  }

  @Test
  void 사용자가_없으면_400이고_Redis를_조회하지_않는다() {
    this.users.clear();

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.actionTokens.lookedUp).isEmpty();
    assertNothingExpensiveRan();
  }

  @Test
  void 탈퇴한_사용자는_400이고_Redis를_조회하지_않는다() {
    this.users.register(withdrawnUser(SNAPSHOT_ROW_VERSION));

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.actionTokens.lookedUp).isEmpty();
    assertNothingExpensiveRan();
  }

  @ParameterizedTest
  @EnumSource(
      value = ActionTokenStore.LookupResult.class,
      names = {
        "NOT_FOUND",
        "NOT_CURRENT",
        "CONSUMED",
        "INVALIDATED",
        "HASH_MISMATCH",
        "MISMATCHED"
      })
  void Redis_사전_조회_부적격은_400이고_유출_조회와_Argon2id를_실행하지_않는다(ActionTokenStore.LookupResult result) {
    this.actionTokens.lookupResult = result;

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    // 이미 소비·무효화된 정상 서명 토큰이 비싼 계산을 반복 유발하지 못한다 (3.4.2절).
    assertNothingExpensiveRan();
    assertThat(this.users.updates).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = ActionTokenStore.ConsumeResult.class,
      names = {"NOT_FOUND", "NOT_CURRENT", "HASH_MISMATCH", "ALREADY_CONSUMED", "INVALIDATED"})
  void 소비_부적격은_400이고_DB와_계열을_건드리지_않는다(ActionTokenStore.ConsumeResult result) {
    // 사전 조회 뒤 다른 요청이 먼저 소비한 경쟁이다. 최종 판정은 원자적 소비다.
    this.actionTokens.consumeResult = result;

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.users.updates).isEmpty();
    assertThat(this.transaction.executions).isZero();
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  // ---------- 비밀번호 정책 ----------

  @Test
  void 정책_위반은_422_INVALID_PASSWORD이고_토큰을_소비하지_않는다() {
    assertThat(errorCodeOf(new PasswordResetService.Command(SAMPLE_COMPACT_VALUE, "짧은 비밀번호")))
        .isEqualTo(ErrorCode.INVALID_PASSWORD);

    assertNothingExpensiveRan();
    assertThat(this.users.updates).isEmpty();
  }

  @Test
  void 유출된_비밀번호도_422이고_토큰을_소비하지_않는다() {
    this.checker.willReturn(CompromisedPasswordChecker.CheckResult.COMPROMISED);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_PASSWORD);
    assertThat(this.hasher.hashCallCount()).isZero();
    assertThat(this.actionTokens.consumeAttempts).isEmpty();
  }

  @Test
  void 유출_조회_장애는_503_PASSWORD_POLICY_UNAVAILABLE이고_토큰을_소비하지_않는다() {
    this.checker.willReturn(CompromisedPasswordChecker.CheckResult.UNAVAILABLE);

    assertThat(errorCode()).isEqualTo(ErrorCode.PASSWORD_POLICY_UNAVAILABLE);
    // 소비 전에 끝나므로 같은 입력과 같은 token으로 다시 시도할 수 있다 (3.6.2절).
    assertThat(this.hasher.hashCallCount()).isZero();
    assertThat(this.actionTokens.consumeAttempts).isEmpty();
    assertThat(this.users.updates).isEmpty();
  }

  // ---------- 저장소 장애 (503) ----------

  @Test
  void 사용자_조회_장애는_400이_아니라_503이다() {
    this.users.willFail(new QueryTimeoutException("응답 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.actionTokens.lookedUp).isEmpty();
  }

  @Test
  void Redis_사전_조회_장애는_503이고_유출_조회와_Argon2id를_실행하지_않는다() {
    this.actionTokens.lookupResult = ActionTokenStore.LookupResult.UNAVAILABLE;

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertNothingExpensiveRan();
  }

  @Test
  void 소비_결과가_불확실하면_503이고_DB를_시작하지_않는다() {
    this.actionTokens.consumeResult = ActionTokenStore.ConsumeResult.UNAVAILABLE;

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    // 실제로 소비됐을 수도, 아닐 수도 있다. 어느 쪽인지 추측해 DB를 바꾸지 않는다 (3.6.2절).
    assertThat(this.transaction.executions).isZero();
    assertThat(this.users.updates).isEmpty();
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  @Test
  void 소비_성공_뒤_DB_실패는_204가_아니라_503이고_토큰을_복원하지_않는다() {
    this.transaction.willFail(new QueryTimeoutException("응답 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.actionTokens.consumeAttempts).hasSize(1);
    assertThat(this.actionTokens.issueCallCount).isZero();
    // DB 성공이 확정되지 않았으므로 사후 정리도 하지 않는다.
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  @Test
  void 소비_성공_뒤_commit_실패나_결과_불확실도_503이다() {
    this.transaction.willFailAfterAction(new TransactionSystemException("commit 결과를 알 수 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.actionTokens.issueCallCount).isZero();
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  // ---------- row_version 경합 ----------

  @Test
  void 인증_필드가_그대로이고_row_version만_바뀌면_같은_해시로_한_번_재시도한다() {
    this.users.willUpdate(0, 1);
    this.users.registerOnReread(user(SNAPSHOT_ROW_VERSION + 3, OLD_HASH, SNAPSHOT_AUTH_VERSION));

    assertThatCode(() -> this.service.complete(command())).doesNotThrowAnyException();

    assertThat(this.users.updates)
        .containsExactly(
            new Update(USER_ID, SNAPSHOT_ROW_VERSION, OLD_HASH, SNAPSHOT_AUTH_VERSION, NEW_HASH),
            new Update(
                USER_ID, SNAPSHOT_ROW_VERSION + 3, OLD_HASH, SNAPSHOT_AUTH_VERSION, NEW_HASH));
    // 이미 계산한 Argon2id 결과를 재사용한다.
    assertThat(this.hasher.hashCallCount()).isEqualTo(1);
    assertThat(this.refreshTokens.revokedUserIds).containsExactly(USER_ID);
  }

  @Test
  void 두_번째_갱신도_0행이면_503이고_세_번째는_없다() {
    this.users.willUpdate(0, 0);
    this.users.registerOnReread(user(SNAPSHOT_ROW_VERSION + 3, OLD_HASH, SNAPSHOT_AUTH_VERSION));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.users.updates).hasSize(2);
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  @Test
  void 재조회에서_password_hash가_바뀌었으면_덮어쓰지_않고_503이다() {
    this.users.willUpdate(0);
    this.users.registerOnReread(
        user(SNAPSHOT_ROW_VERSION + 1, "$argon2id$other", SNAPSHOT_AUTH_VERSION));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.users.updates).hasSize(1);
  }

  @Test
  void 재조회에서_auth_version이_바뀌었으면_덮어쓰지_않고_503이다() {
    this.users.willUpdate(0);
    this.users.registerOnReread(
        user(SNAPSHOT_ROW_VERSION + 1, OLD_HASH, SNAPSHOT_AUTH_VERSION + 1));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.users.updates).hasSize(1);
  }

  @Test
  void 재조회에서_사용자가_사라졌으면_400이다() {
    this.users.willUpdate(0);
    this.users.clearOnReread();

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.users.updates).hasSize(1);
  }

  @Test
  void 재조회에서_탈퇴했으면_400이다() {
    this.users.willUpdate(0);
    this.users.registerOnReread(withdrawnUser(SNAPSHOT_ROW_VERSION + 1));

    assertThat(errorCode()).isEqualTo(ErrorCode.ACTION_TOKEN_INVALID);
    assertThat(this.users.updates).hasSize(1);
  }

  @Test
  void 경합_재조회_장애는_503이다() {
    this.users.willUpdate(0);
    this.users.willFailOnSecondRead(new CannotCreateTransactionException("연결 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  // ---------- Refresh 계열 사후 정리 ----------

  @Test
  void 계열_정리가_끝나지_않아도_성공이다() {
    this.refreshTokens.result = RefreshTokenStore.AllFamiliesRevocationResult.UNAVAILABLE;

    assertThatCode(() -> this.service.complete(command())).doesNotThrowAnyException();
    assertThat(this.users.updates).hasSize(1);
  }

  @Test
  void 계열_정리가_예외로_끝나도_성공이다() {
    this.refreshTokens.failure = new IllegalStateException("정리 실패");

    // DB commit은 이미 확정됐다. 무효화 권위는 auth_version이라 여기서 실패로 보고하지 않는다 (3.6.2절).
    assertThatCode(() -> this.service.complete(command())).doesNotThrowAnyException();
  }

  @Test
  void 계열_정리_실패_로그에_토큰_식별자_해시_비밀번호_예외가_없다() {
    this.refreshTokens.failure =
        new IllegalStateException("test:auth:{" + USER_ID + "}:families " + JTI);
    CapturingAppender appender = CapturingAppender.attachTo(PasswordResetService.class);
    try {
      this.service.complete(command());

      List<ILoggingEvent> logged = appender.events();
      assertThat(logged).isNotEmpty();
      for (ILoggingEvent event : logged) {
        assertThat(event.getFormattedMessage())
            .doesNotContain(SAMPLE_COMPACT_VALUE)
            .doesNotContain(JTI)
            .doesNotContain(TOKEN_HASH)
            .doesNotContain(NEW_PASSWORD)
            .doesNotContain(NEW_HASH)
            .doesNotContain(OLD_HASH)
            .doesNotContain("families")
            .doesNotContain(Long.toString(USER_ID));
        assertThat(event.getThrowableProxy()).isNull();
      }
    } finally {
      appender.detach();
    }
  }

  @Test
  void 결과가_불확실한_정리도_운영_경고를_남긴다() {
    this.refreshTokens.result = RefreshTokenStore.AllFamiliesRevocationResult.UNAVAILABLE;
    CapturingAppender appender = CapturingAppender.attachTo(PasswordResetService.class);
    try {
      this.service.complete(command());

      assertThat(appender.events()).singleElement();
    } finally {
      appender.detach();
    }
  }

  // ---------- 노출 ----------

  @Test
  void 오류_message에_토큰도_비밀번호도_식별자도_없다() {
    this.actionTokens.lookupResult = ActionTokenStore.LookupResult.CONSUMED;

    BusinessException thrown =
        catchThrowableOfType(BusinessException.class, () -> this.service.complete(command()));

    assertThat(thrown.getMessage())
        .doesNotContain(SAMPLE_COMPACT_VALUE)
        .doesNotContain(NEW_PASSWORD)
        .doesNotContain(JTI)
        .doesNotContain(TOKEN_HASH)
        .doesNotContain(Long.toString(USER_ID));
    assertThat(thrown.getCause()).isNull();
  }

  @Test
  void Command의_toString은_값을_가린다() {
    assertThat(command()).hasToString("PasswordResetCommand[REDACTED]");
  }

  // ---------- 도우미 ----------

  private record Update(
      long userId,
      long expectedRowVersion,
      String expectedPasswordHash,
      long expectedAuthVersion,
      String newPasswordHash) {}

  private final class StubTokenVerifier implements PasswordResetTokenVerifier {

    private final List<String> submitted = new ArrayList<>();
    private ActionTokenVerification result;

    void willReturn(ActionTokenVerification result) {
      this.result = result;
    }

    @Override
    public ActionTokenVerification verify(String compactToken) {
      PasswordResetServiceTest.this.calls.add("verify");
      this.submitted.add(compactToken);
      return this.result;
    }
  }

  private final class RecordingActionTokenStore implements ActionTokenStore {

    private final List<ActionTokenStore.TokenRecord> lookedUp = new ArrayList<>();
    private final List<ActionTokenStore.TokenRecord> consumeAttempts = new ArrayList<>();
    private ActionTokenStore.LookupResult lookupResult = ActionTokenStore.LookupResult.ACTIVE;
    private ActionTokenStore.ConsumeResult consumeResult = ActionTokenStore.ConsumeResult.CONSUMED;
    private int issueCallCount;

    @Override
    public ActionTokenStore.IssueResult issueActionToken(ActionTokenStore.TokenRecord token) {
      // 완료 경로에는 발급이 없다. 호출되면 소비한 토큰을 되살리는 보상을 한 것이다.
      this.issueCallCount++;
      throw new UnsupportedOperationException("완료 경로에서 토큰을 발급하지 않는다.");
    }

    @Override
    public ActionTokenStore.LookupResult lookup(ActionTokenStore.TokenRecord submitted) {
      PasswordResetServiceTest.this.calls.add("lookup");
      this.lookedUp.add(submitted);
      return this.lookupResult;
    }

    @Override
    public ActionTokenStore.ConsumeResult consume(ActionTokenStore.TokenRecord submitted) {
      PasswordResetServiceTest.this.calls.add("consume");
      this.consumeAttempts.add(submitted);
      return this.consumeResult;
    }
  }

  private final class RecordingUserRepository implements UserRepository {

    private final List<Update> updates = new ArrayList<>();
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
      PasswordResetServiceTest.this.calls.add("findById");
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
    public int resetPassword(
        long userId,
        long expectedRowVersion,
        String expectedPasswordHash,
        long expectedAuthVersion,
        String newPasswordHash,
        LocalDateTime now) {
      PasswordResetServiceTest.this.calls.add("resetPassword");
      this.updates.add(
          new Update(
              userId,
              expectedRowVersion,
              expectedPasswordHash,
              expectedAuthVersion,
              newPasswordHash));
      int index = this.updates.size() - 1;
      return index < this.updateResults.size() ? this.updateResults.get(index) : 0;
    }

    @Override
    public int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
      throw new UnsupportedOperationException("비밀번호 재설정 경로에서는 쓰지 않는다.");
    }

    @Override
    public int recordLoginFailure(
        long userId, int maxAttempts, LocalDateTime lockedUntil, LocalDateTime now) {
      throw new UnsupportedOperationException("비밀번호 재설정 경로에서는 쓰지 않는다.");
    }

    @Override
    public int recordLoginSuccess(
        long userId, long expectedRowVersion, long expectedAuthVersion, LocalDateTime now) {
      throw new UnsupportedOperationException("비밀번호 재설정 경로에서는 쓰지 않는다.");
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

  private final class RecordingRefreshTokenStore implements RefreshTokenStore {

    private final List<Long> revokedUserIds = new ArrayList<>();
    private RefreshTokenStore.AllFamiliesRevocationResult result =
        RefreshTokenStore.AllFamiliesRevocationResult.REVOKED;
    private RuntimeException failure;

    @Override
    public RefreshTokenStore.AllFamiliesRevocationResult revokeAllFamilies(long userId) {
      PasswordResetServiceTest.this.calls.add("revokeAllFamilies");
      this.revokedUserIds.add(userId);
      if (this.failure != null) {
        throw this.failure;
      }
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
