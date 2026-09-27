package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.support.log.CapturingAppender;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 회원 탈퇴의 순서와 실패 분류를 저장소 없이 고정한다 (API 명세 3.2.1·3.6.2절, ERD 3.1절).
 *
 * <p>여기서 확인하는 것은 "무엇을 어떤 순서로 하고, 각 실패를 422·401·403·503 중 무엇으로 접으며, 무엇을 실행하지 않는가"다. 실제 조건부
 * UPDATE·UNIQUE 충돌·연결 데이터 보존은 MariaDB 테스트가, 실제 Refresh 계열 폐기는 Redis 테스트가 맡는다.
 */
class UserWithdrawalServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
  private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
  private static final long USER_ID = 42L;
  private static final long SNAPSHOT_ROW_VERSION = 7L;
  private static final long SNAPSHOT_AUTH_VERSION = 3L;
  private static final String USERNAME = "learner01";
  private static final String EMAIL = "learner01@example.com";
  private static final String CURRENT_PASSWORD = "충분히 긴 현재 비밀번호 문구";
  private static final String OLD_HASH = "$argon2id$v=19$m=19456,t=2,p=1$old-salt$old-hash";
  private static final String OTHER_HASH = "$argon2id$v=19$m=19456,t=2,p=1$other-salt$other-hash";

  /** {@link RecordingPasswordHasher}가 돌려주는 고정 인코딩 결과. */
  private static final String DISCARDED_HASH = "$argon2id$v=19$m=19456,t=2,p=1$fake-salt$fake-hash";

  private static final Pattern PSEUDONYM_USERNAME = Pattern.compile("^withdrawn-[0-9a-f]{32}$");
  private static final Pattern PSEUDONYM_EMAIL =
      Pattern.compile("^withdrawn-[0-9a-f]{32}@withdrawn\\.invalid$");

  /** Base64URL(패딩 없음)로 인코딩한 32바이트. */
  private static final Pattern DISCARDED_SECRET = Pattern.compile("^[A-Za-z0-9_-]{43}$");

  private static final UUID FIRST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SECOND = UUID.fromString("22222222-2222-4222-8222-222222222222");

  /** 협력 객체 호출 순서. 순서가 계약이라 개별 호출 여부만으로는 부족하다. */
  private final List<String> calls = new ArrayList<>();

  private final Deque<UUID> pseudonymSources = new ArrayDeque<>();

  private RecordingUserRepository users;
  private RecordingPasswordHasher hasher;
  private RecordingRefreshTokenStore refreshTokens;
  private RecordingTransactionOperations transaction;
  private UserWithdrawalService service;

  @BeforeEach
  void setUp() {
    this.users = new RecordingUserRepository();
    this.refreshTokens = new RecordingRefreshTokenStore();
    this.transaction = new RecordingTransactionOperations();
    this.hasher =
        new RecordingPasswordHasher() {
          @Override
          public PasswordHasher.EncodedPassword hash(String normalizedPassword) {
            UserWithdrawalServiceTest.this.calls.add("hash");
            return super.hash(normalizedPassword);
          }

          @Override
          public boolean matches(String normalizedPassword, String encodedHash) {
            UserWithdrawalServiceTest.this.calls.add("matches");
            return super.matches(normalizedPassword, encodedHash);
          }
        };
    this.service =
        new UserWithdrawalService(
            this.users,
            new PasswordVerificationService(this.hasher),
            this.hasher,
            this.refreshTokens,
            this.transaction,
            this::nextPseudonymSource,
            Clock.fixed(NOW, ZoneOffset.UTC));

    this.users.register(user(SNAPSHOT_ROW_VERSION, OLD_HASH, SNAPSHOT_AUTH_VERSION, true));
  }

  private UUID nextPseudonymSource() {
    UUID queued = this.pseudonymSources.poll();
    return queued != null ? queued : UUID.randomUUID();
  }

  private static User user(
      long rowVersion, String passwordHash, long authVersion, boolean emailVerified) {
    User user = User.create(USERNAME, passwordHash, UserRole.LEARNER, Email.of(EMAIL), NOW_UTC);
    if (emailVerified) {
      user.verifyEmail(NOW_UTC);
    }
    assign(user, "id", USER_ID);
    assign(user, "rowVersion", rowVersion);
    assign(user, "authVersion", authVersion);
    return user;
  }

  private static User withdrawnUser(long rowVersion) {
    User user = user(rowVersion, OLD_HASH, SNAPSHOT_AUTH_VERSION, true);
    user.withdraw(
        "withdrawn-1", Email.of("withdrawn-1@example.invalid"), "discarded$hash", NOW_UTC);
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

  private static UserWithdrawalService.Command command(String currentPassword) {
    return new UserWithdrawalService.Command(USER_ID, currentPassword);
  }

  private BusinessException thrownBy(String currentPassword) {
    return catchThrowableOfType(
        BusinessException.class, () -> this.service.withdraw(command(currentPassword)));
  }

  private ErrorCode errorCodeOf(String currentPassword) {
    return thrownBy(currentPassword).getErrorCode();
  }

  private ErrorCode errorCode() {
    return errorCodeOf(CURRENT_PASSWORD);
  }

  private static DataIntegrityViolationException violation(ConstraintKind kind) {
    return new DataIntegrityViolationException(
        "무결성 위반",
        new ConstraintViolationException(
            "제약 위반",
            new java.sql.SQLIntegrityConstraintViolationException("제약 위반", "23000", 1062),
            kind,
            "some_constraint"));
  }

  private static String hex(UUID uuid) {
    return uuid.toString().replace("-", "");
  }

  private void assertNothingChanged() {
    assertThat(this.users.updates).isEmpty();
    assertThat(this.transaction.executions).isZero();
    assertThat(this.hasher.hashCallCount()).isZero();
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  // ---------- 성공 ----------

  @Test
  void 현재_비밀번호가_맞으면_Snapshot_조건으로_탈퇴를_확정하고_Refresh_계열을_정리한다() {
    assertThatCode(() -> this.service.withdraw(command(CURRENT_PASSWORD)))
        .doesNotThrowAnyException();

    assertThat(this.users.updates).singleElement();
    Update update = this.users.updates.get(0);
    assertThat(update.userId()).isEqualTo(USER_ID);
    assertThat(update.expectedRowVersion()).isEqualTo(SNAPSHOT_ROW_VERSION);
    assertThat(update.expectedPasswordHash()).isEqualTo(OLD_HASH);
    assertThat(update.expectedAuthVersion()).isEqualTo(SNAPSHOT_AUTH_VERSION);
    assertThat(update.discardedPasswordHash()).isEqualTo(DISCARDED_HASH);
    assertThat(update.now()).isEqualTo(NOW_UTC);
    assertThat(this.transaction.executions).isEqualTo(1);
    assertThat(this.refreshTokens.revokedUserIds).containsExactly(USER_ID);
  }

  @Test
  void 처리_순서가_계약과_같다() {
    this.service.withdraw(command(CURRENT_PASSWORD));

    // 검증과 해시는 트랜잭션 밖에서 끝내고, Redis 정리는 commit 뒤에 한다.
    assertThat(this.calls)
        .containsExactly(
            "findById", "matches", "hash", "transaction", "withdraw", "revokeAllFamilies");
  }

  @Test
  void 현재_비밀번호를_trim하지_않고_검증기에_넘긴다() {
    String spaced = "  " + CURRENT_PASSWORD + "  ";

    this.service.withdraw(command(spaced));

    assertThat(this.hasher.verifiedPasswords()).containsExactly(spaced);
  }

  @Test
  void 공백만_있는_값은_누락이_아니라_저장된_비밀번호와_비교한다() {
    this.hasher.willReturnMatches(false);

    assertThat(errorCodeOf("   ")).isEqualTo(ErrorCode.CURRENT_PASSWORD_MISMATCH);
    assertThat(this.hasher.verifiedPasswords()).containsExactly("   ");
  }

  @Test
  void 폐기_비밀번호는_256비트_무작위_비밀을_기존_해시기로_한_번만_해시한다() {
    this.service.withdraw(command(CURRENT_PASSWORD));
    this.service.withdraw(command(CURRENT_PASSWORD));

    List<String> secrets = this.hasher.hashedPasswords();
    assertThat(secrets).hasSize(2);
    assertThat(secrets).allSatisfy(secret -> assertThat(secret).matches(DISCARDED_SECRET));
    assertThat(secrets.get(0)).isNotEqualTo(secrets.get(1)).isNotEqualTo(CURRENT_PASSWORD);
  }

  @Test
  void 가명은_원래_식별자와_무관한_무작위_값이고_컬럼_길이_안이다() {
    this.service.withdraw(command(CURRENT_PASSWORD));
    this.service.withdraw(command(CURRENT_PASSWORD));

    Update first = this.users.updates.get(0);
    Update second = this.users.updates.get(1);
    for (Update update : List.of(first, second)) {
      assertThat(update.pseudonymUsername())
          .matches(PSEUDONYM_USERNAME)
          .hasSizeLessThanOrEqualTo(50)
          .doesNotContain(USERNAME);
      assertThat(update.pseudonymEmail())
          .matches(PSEUDONYM_EMAIL)
          .hasSizeLessThanOrEqualTo(Email.MAX_LENGTH)
          .doesNotContain(USERNAME);
    }
    assertThat(first.pseudonymUsername()).isNotEqualTo(second.pseudonymUsername());
    assertThat(first.pseudonymEmail()).isNotEqualTo(second.pseudonymEmail());
  }

  // ---------- 입력·사용자 상태 ----------

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {""})
  void 누락_null_빈_문자열은_422_INVALID_ACCOUNT_INPUT이고_사용자를_조회하지_않는다(String value) {
    assertThat(errorCodeOf(value)).isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);

    assertThat(this.calls).isEmpty();
    assertNothingChanged();
  }

  @Test
  void 사용자가_없으면_401이고_비밀번호를_검증하지_않는다() {
    this.users.clear();

    assertThat(errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
    assertThat(this.hasher.matchesCallCount()).isZero();
    assertNothingChanged();
  }

  @Test
  void 탈퇴한_사용자는_401이다() {
    this.users.register(withdrawnUser(SNAPSHOT_ROW_VERSION));

    assertThat(errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
    assertThat(this.hasher.matchesCallCount()).isZero();
    assertNothingChanged();
  }

  @Test
  void 이메일_미인증_사용자는_401이다() {
    this.users.register(user(SNAPSHOT_ROW_VERSION, OLD_HASH, SNAPSHOT_AUTH_VERSION, false));

    assertThat(errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
    assertNothingChanged();
  }

  @Test
  void 사용자_조회_장애는_401이_아니라_503이다() {
    this.users.willFail(new QueryTimeoutException("응답 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.hasher.matchesCallCount()).isZero();
    assertNothingChanged();
  }

  // ---------- 현재 비밀번호 불일치 ----------

  @Test
  void 비밀번호가_다르면_403이고_해시_트랜잭션_Redis_어느_것도_실행하지_않는다() {
    this.hasher.willReturnMatches(false);

    assertThat(errorCode()).isEqualTo(ErrorCode.CURRENT_PASSWORD_MISMATCH);

    assertThat(this.calls).containsExactly("findById", "matches");
    assertNothingChanged();
  }

  // ---------- 가명 UNIQUE 충돌 ----------

  @Test
  void 가명_UNIQUE_충돌은_rollback_뒤_새_가명으로_재시도해_성공한다() {
    this.pseudonymSources.add(FIRST);
    this.pseudonymSources.add(SECOND);
    this.users.willFailOnWithdraw(violation(ConstraintKind.UNIQUE));

    assertThatCode(() -> this.service.withdraw(command(CURRENT_PASSWORD)))
        .doesNotThrowAnyException();

    assertThat(this.users.updates).hasSize(2);
    assertThat(this.users.updates.get(0).pseudonymUsername()).isEqualTo("withdrawn-" + hex(FIRST));
    assertThat(this.users.updates.get(1).pseudonymUsername()).isEqualTo("withdrawn-" + hex(SECOND));
    assertThat(this.users.updates.get(1).pseudonymEmail())
        .isEqualTo("withdrawn-" + hex(SECOND) + "@withdrawn.invalid");
    // 재시도는 가명만 바꾼다. 비밀번호 확인·폐기 해시·Snapshot을 다시 만들지 않는다.
    assertThat(this.users.updates)
        .extracting(Update::expectedRowVersion, Update::discardedPasswordHash)
        .containsOnly(org.assertj.core.groups.Tuple.tuple(SNAPSHOT_ROW_VERSION, DISCARDED_HASH));
    assertThat(this.hasher.hashCallCount()).isEqualTo(1);
    assertThat(this.hasher.matchesCallCount()).isEqualTo(1);
    assertThat(this.calls).containsOnlyOnce("findById");
    assertThat(this.transaction.executions).isEqualTo(2);
    assertThat(this.refreshTokens.revokedUserIds).containsExactly(USER_ID);
  }

  @Test
  void 가명_충돌이_상한까지_이어지면_503이고_Refresh_계열을_정리하지_않는다() {
    for (int i = 0; i < UserWithdrawalService.MAX_PSEUDONYM_ATTEMPTS; i++) {
      this.users.willFailOnWithdraw(violation(ConstraintKind.UNIQUE));
    }

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(this.users.updates).hasSize(UserWithdrawalService.MAX_PSEUDONYM_ATTEMPTS);
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(value = ConstraintKind.class, mode = EnumSource.Mode.EXCLUDE, names = "UNIQUE")
  void UNIQUE가_아닌_무결성_위반은_재시도하지_않고_503이다(ConstraintKind kind) {
    this.users.willFailOnWithdraw(violation(kind));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(this.users.updates).hasSize(1);
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  @Test
  void 종류를_알_수_없는_무결성_위반은_UNIQUE로_추측하지_않고_503이다() {
    this.users.willFailOnWithdraw(new DataIntegrityViolationException("원인 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(this.users.updates).hasSize(1);
  }

  // ---------- DB 장애·결과 불확실 ----------

  @Test
  void 갱신_중_DB_장애는_재시도하지_않고_503이다() {
    this.users.willFailOnWithdraw(new QueryTimeoutException("응답 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(this.users.updates).hasSize(1);
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  @Test
  void commit_실패나_결과_불확실은_재시도하지_않고_503이다() {
    this.transaction.willFailAfterAction(new TransactionSystemException("commit 결과 불명"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(this.users.updates).hasSize(1);
    assertThat(this.transaction.executions).isEqualTo(1);
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  @Test
  void 트랜잭션을_시작하지_못하면_503이다() {
    this.transaction.willFail(new CannotCreateTransactionException("연결 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(this.users.updates).isEmpty();
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  // ---------- 0행 재조회 ----------

  @Test
  void 재조회에서_사용자가_사라졌으면_401이다() {
    this.users.willUpdate(0);
    this.users.clearOnReread();

    assertThat(errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  @Test
  void 재조회에서_이미_탈퇴했으면_401이다() {
    this.users.willUpdate(0);
    this.users.registerOnReread(withdrawnUser(SNAPSHOT_ROW_VERSION + 1));

    assertThat(errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  @Test
  void 재조회에서_password_hash가_바뀌었으면_503이다() {
    this.users.willUpdate(0);
    this.users.registerOnReread(
        user(SNAPSHOT_ROW_VERSION + 1, OTHER_HASH, SNAPSHOT_AUTH_VERSION, true));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.users.updates).hasSize(1);
  }

  @Test
  void 재조회에서_auth_version이_바뀌었으면_503이다() {
    this.users.willUpdate(0);
    this.users.registerOnReread(
        user(SNAPSHOT_ROW_VERSION + 1, OLD_HASH, SNAPSHOT_AUTH_VERSION + 1, true));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.users.updates).hasSize(1);
  }

  @Test
  void row_version만_바뀌어도_새_재시도_계약_없이_503이다() {
    this.users.willUpdate(0);
    this.users.registerOnReread(
        user(SNAPSHOT_ROW_VERSION + 1, OLD_HASH, SNAPSHOT_AUTH_VERSION, true));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(this.users.updates).hasSize(1);
    assertThat(this.hasher.hashCallCount()).isEqualTo(1);
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  @Test
  void 재조회_장애는_503이다() {
    this.users.willUpdate(0);
    this.users.willFailOnSecondRead(new QueryTimeoutException("응답 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  // ---------- Refresh 계열 사후 정리 ----------

  @Test
  void 계열_정리가_끝나지_않아도_성공이고_단계만_경고로_남긴다() {
    this.refreshTokens.result = RefreshTokenStore.AllFamiliesRevocationResult.UNAVAILABLE;
    CapturingAppender appender = CapturingAppender.attachTo(UserWithdrawalService.class);
    try {
      assertThatCode(() -> this.service.withdraw(command(CURRENT_PASSWORD)))
          .doesNotThrowAnyException();

      assertThat(appender.events())
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                    .contains("api=user-withdrawal")
                    .contains("stage=unavailable");
              });
    } finally {
      appender.detach();
    }
    assertThat(this.users.updates).hasSize(1);
  }

  @Test
  void 계열_정리가_예외로_끝나도_성공이고_로그에_식별자_비밀번호_해시_가명_예외가_없다() {
    this.refreshTokens.failure =
        new IllegalStateException(
            "test:auth:{" + USER_ID + "}:families " + EMAIL + " " + CURRENT_PASSWORD);
    CapturingAppender appender = CapturingAppender.attachTo(UserWithdrawalService.class);
    try {
      // DB commit은 이미 확정됐다. 무효화 권위는 status·auth_version이라 여기서 실패로 보고하지 않는다 (3.6.2절).
      assertThatCode(() -> this.service.withdraw(command(CURRENT_PASSWORD)))
          .doesNotThrowAnyException();

      Update update = this.users.updates.get(0);
      List<ILoggingEvent> logged = appender.events();
      assertThat(logged)
          .singleElement()
          .satisfies(event -> assertThat(event.getFormattedMessage()).contains("stage=exception"));
      for (ILoggingEvent event : logged) {
        assertThat(event.getFormattedMessage())
            .doesNotContain(CURRENT_PASSWORD)
            .doesNotContain(OLD_HASH)
            .doesNotContain(DISCARDED_HASH)
            .doesNotContain(this.hasher.hashedPasswords().get(0))
            .doesNotContain(USERNAME)
            .doesNotContain(EMAIL)
            .doesNotContain(update.pseudonymUsername())
            .doesNotContain(update.pseudonymEmail())
            .doesNotContain("families")
            .doesNotContain(Long.toString(USER_ID));
        assertThat(event.getThrowableProxy()).isNull();
      }
    } finally {
      appender.detach();
    }
  }

  // ---------- 노출 ----------

  @Test
  void 오류_message와_원인에_비밀번호_해시_식별자가_없다() {
    this.hasher.willReturnMatches(false);
    BusinessException mismatch = thrownBy(CURRENT_PASSWORD);

    this.hasher.willReturnMatches(true);
    for (int i = 0; i < UserWithdrawalService.MAX_PSEUDONYM_ATTEMPTS; i++) {
      this.users.willFailOnWithdraw(violation(ConstraintKind.UNIQUE));
    }
    BusinessException exhausted = thrownBy(CURRENT_PASSWORD);

    for (BusinessException thrown : List.of(mismatch, exhausted)) {
      assertThat(thrown.getMessage())
          .doesNotContain(CURRENT_PASSWORD)
          .doesNotContain(OLD_HASH)
          .doesNotContain(DISCARDED_HASH)
          .doesNotContain(USERNAME)
          .doesNotContain(EMAIL)
          .doesNotContain("withdrawn")
          .doesNotContain(Long.toString(USER_ID));
      assertThat(thrown.getCause()).isNull();
    }
  }

  @Test
  void Command의_toString은_값을_가린다() {
    assertThat(command(CURRENT_PASSWORD))
        .hasToString("UserWithdrawalCommand[REDACTED]")
        .asString()
        .doesNotContain(CURRENT_PASSWORD)
        .doesNotContain(Long.toString(USER_ID));
  }

  // ---------- 도우미 ----------

  private record Update(
      long userId,
      long expectedRowVersion,
      String expectedPasswordHash,
      long expectedAuthVersion,
      String pseudonymUsername,
      String pseudonymEmail,
      String discardedPasswordHash,
      LocalDateTime now) {}

  private final class RecordingUserRepository implements UserRepository {

    private final List<Update> updates = new ArrayList<>();
    private final Deque<Integer> updateResults = new ArrayDeque<>();
    private final Deque<RuntimeException> withdrawFailures = new ArrayDeque<>();

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

    void willUpdate(int result) {
      this.updateResults.add(result);
    }

    /** 다음 갱신 한 번을 이 예외로 실패시킨다. 여러 번 부르면 순서대로 쓴다. */
    void willFailOnWithdraw(RuntimeException failure) {
      this.withdrawFailures.add(failure);
    }

    @Override
    public Optional<User> findById(Long id) {
      UserWithdrawalServiceTest.this.calls.add("findById");
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
    public int withdraw(
        long userId,
        long expectedRowVersion,
        String expectedPasswordHash,
        long expectedAuthVersion,
        String pseudonymUsername,
        Email pseudonymEmail,
        String discardedPasswordHash,
        LocalDateTime now) {
      UserWithdrawalServiceTest.this.calls.add("withdraw");
      this.updates.add(
          new Update(
              userId,
              expectedRowVersion,
              expectedPasswordHash,
              expectedAuthVersion,
              pseudonymUsername,
              pseudonymEmail.getValue(),
              discardedPasswordHash,
              now));
      RuntimeException withdrawFailure = this.withdrawFailures.poll();
      if (withdrawFailure != null) {
        throw withdrawFailure;
      }
      Integer result = this.updateResults.poll();
      return result != null ? result : 1;
    }

    @Override
    public int resetPassword(
        long userId,
        long expectedRowVersion,
        String expectedPasswordHash,
        long expectedAuthVersion,
        String newPasswordHash,
        LocalDateTime now) {
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

    /** 탈퇴는 엔티티 저장으로 행을 바꾸지 않는다. 조건부 UPDATE만 쓴다. */
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

  /** 탈퇴는 전체 계열 정리만 쓴다. 토큰 발급·회전·개별 폐기는 호출되면 실패한다. */
  private final class RecordingRefreshTokenStore implements RefreshTokenStore {

    private final List<Long> revokedUserIds = new ArrayList<>();
    private RefreshTokenStore.AllFamiliesRevocationResult result =
        RefreshTokenStore.AllFamiliesRevocationResult.REVOKED;
    private RuntimeException failure;

    @Override
    public RefreshTokenStore.AllFamiliesRevocationResult revokeAllFamilies(long userId) {
      UserWithdrawalServiceTest.this.calls.add("revokeAllFamilies");
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
  private final class RecordingTransactionOperations implements TransactionOperations {

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
      UserWithdrawalServiceTest.this.calls.add("transaction");
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
