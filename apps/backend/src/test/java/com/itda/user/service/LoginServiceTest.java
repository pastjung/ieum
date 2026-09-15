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
import com.itda.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 로그인의 판정과 순서를 저장소 없이 고정한다 (API 명세 3.3.1·3.6.2절, ERD 3.1절).
 *
 * <p>여기서 확인하는 것은 "무엇을 어떤 code로 거부하고, 어떤 순서로 저장소를 건드리는가"다. 실제 원자 UPDATE와 Redis 원자성은 가짜 구현으로 증명되지
 * 않으므로 MariaDB·Redis 테스트가 맡는다.
 */
class LoginServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
  private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
  private static final long USER_ID = 42L;
  private static final String USERNAME = "learner01";
  private static final String PASSWORD = "충분히 긴 로그인 비밀번호";
  private static final String STORED_HASH = "$argon2id$stored";
  private static final long SNAPSHOT_ROW_VERSION = 3L;
  private static final long SNAPSHOT_AUTH_VERSION = 7L;

  private RecordingUserRepository users;
  private RecordingPasswordHasher hasher;
  private RecordingAccessTokenIssuer accessTokens;
  private RecordingRefreshTokenIssuer refreshTokens;
  private RecordingRefreshTokenStore store;
  private RecordingTransactionOperations transaction;
  private LoginService service;

  @BeforeEach
  void setUp() {
    this.users = new RecordingUserRepository();
    this.hasher = new RecordingPasswordHasher();
    this.accessTokens = new RecordingAccessTokenIssuer();
    this.refreshTokens = new RecordingRefreshTokenIssuer();
    this.store = new RecordingRefreshTokenStore();
    this.transaction = new RecordingTransactionOperations();
    this.service =
        new LoginService(
            this.users,
            new PasswordVerificationService(this.hasher),
            new DecoyPasswordHash(this.hasher),
            this.accessTokens,
            this.refreshTokens,
            this.store,
            this.transaction,
            Clock.fixed(NOW, ZoneOffset.UTC));

    this.users.register(activeVerifiedUser());
    this.hasher.willReturnMatches(true);
    this.users.willResetFailures(1);
  }

  private static User activeVerifiedUser() {
    User user =
        User.create(USERNAME, STORED_HASH, UserRole.LEARNER, Email.of("a@example.com"), NOW_UTC);
    user.verifyEmail(NOW_UTC);
    assign(user, "id", USER_ID);
    assign(user, "rowVersion", SNAPSHOT_ROW_VERSION);
    assign(user, "authVersion", SNAPSHOT_AUTH_VERSION);
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

  private AuthSession login() {
    return this.service.login(new LoginService.Command(USERNAME, PASSWORD));
  }

  private ErrorCode errorCode() {
    return catchThrowableOfType(BusinessException.class, this::login).getErrorCode();
  }

  // ---------- 성공 ----------

  @Test
  void 자격_증명이_맞으면_세션을_만든다() {
    AuthSession session = login();

    assertThat(session.accessToken()).isEqualTo(this.accessTokens.issued.get(0).compactToken());
    assertThat(session.refreshToken()).isEqualTo(this.refreshTokens.issued.get(0).compactToken());
    assertThat(session.user().userId()).isEqualTo(USER_ID);
    assertThat(session.user().username()).isEqualTo(USERNAME);
  }

  @Test
  void 만료_시각은_발급기가_돌려준_값_그대로다() {
    AuthSession session = login();

    AccessTokenIssuer.IssuedToken access = this.accessTokens.issued.get(0);
    RefreshTokenIssuer.IssuedToken refresh = this.refreshTokens.issued.get(0);
    // 호출자가 TTL을 다시 더하거나 JWT를 재파싱하지 않는다.
    assertThat(session.accessTokenExpiresAt())
        .isEqualTo(LocalDateTime.ofEpochSecond(access.expiresAtEpochSecond(), 0, ZoneOffset.UTC));
    assertThat(session.refreshTokenExpiresAt())
        .isEqualTo(LocalDateTime.ofEpochSecond(refresh.expiresAtEpochSecond(), 0, ZoneOffset.UTC));
  }

  @Test
  void Snapshot의_authVersion으로_발급한다() {
    // 재조회한 최신 값을 붙이면 구 자격 증명으로 최신 세대의 세션을 만들게 된다 (ERD 3.1절).
    login();

    assertThat(this.accessTokens.issued).hasSize(1);
    assertThat(this.accessTokens.claims.get(0).authVersion()).isEqualTo(SNAPSHOT_AUTH_VERSION);
    assertThat(this.accessTokens.claims.get(0).role()).isEqualTo(UserRole.LEARNER);
    assertThat(this.refreshTokens.claims.get(0).authVersion()).isEqualTo(SNAPSHOT_AUTH_VERSION);
  }

  @Test
  void 성공_초기화는_Snapshot의_row_version과_auth_version을_조건으로_한다() {
    login();

    assertThat(this.users.resets)
        .containsExactly(new Reset(USER_ID, SNAPSHOT_ROW_VERSION, SNAPSHOT_AUTH_VERSION));
    assertThat(this.users.failures).isEmpty();
  }

  @Test
  void DB_초기화가_커밋된_뒤에_토큰을_만든다() {
    login();

    // 커밋 전에 발급하면 실패한 로그인의 토큰이 나갈 수 있다.
    assertThat(this.accessTokens.resetCountWhenIssued).containsExactly(1);
  }

  @Test
  void Redis_등록이_확정된_뒤에만_세션을_돌려준다() {
    login();

    assertThat(this.store.issued).hasSize(1);
    RefreshTokenStore.TokenRecord record = this.store.issued.get(0);
    assertThat(record.userId()).isEqualTo(USER_ID);
    // 해시는 발급기가 만든 값을 그대로 쓴다. 유스케이스가 원문을 해시하지 않는다.
    assertThat(record.tokenHash()).isEqualTo(this.refreshTokens.issued.get(0).tokenHash());
    assertThat(record.jti()).isEqualTo(this.refreshTokens.issued.get(0).jti());
  }

  @Test
  void 로그인마다_다른_familyId를_쓴다() {
    this.users.willResetFailures(1, 1);

    login();
    login();

    assertThat(this.refreshTokens.claims.get(0).familyId())
        .isNotEqualTo(this.refreshTokens.claims.get(1).familyId());
  }

  // ---------- 입력 형식 (422) ----------

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "  ", "ab", "-abc", "abc-", "learner@01", "러너학습자"})
  void username_누락과_형식_위반은_422다(String username) {
    BusinessException thrown =
        catchThrowableOfType(
            BusinessException.class,
            () -> this.service.login(new LoginService.Command(username, PASSWORD)));

    assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);
    // 형식이 틀린 요청으로 DB나 Argon2id를 건드리지 않는다.
    assertThat(this.users.lookups).isZero();
    assertThat(this.hasher.matchesCallCount()).isZero();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {""})
  void password_누락과_빈_문자열은_422다(String password) {
    BusinessException thrown =
        catchThrowableOfType(
            BusinessException.class,
            () -> this.service.login(new LoginService.Command(USERNAME, password)));

    assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);
    assertThat(this.users.lookups).isZero();
  }

  @Test
  void 공백만_있는_비밀번호는_형식_위반이_아니다() {
    // 비밀번호는 trim하지 않는다. 공백도 값의 일부다.
    this.hasher.willReturnMatches(false);

    BusinessException thrown =
        catchThrowableOfType(
            BusinessException.class,
            () -> this.service.login(new LoginService.Command(USERNAME, "   ")));

    assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    assertThat(this.hasher.verifiedPasswords()).containsExactly("   ");
  }

  @Test
  void username은_trim과_NFC_정규화_뒤_조회한다() {
    this.service.login(new LoginService.Command("  " + USERNAME + "\t", PASSWORD));

    assertThat(this.users.lookedUpUsernames).containsExactly(USERNAME);
  }

  // ---------- 자격 증명 실패 (401) ----------

  @Test
  void 계정이_없어도_미끼_해시로_검증을_한_번_수행한다() {
    this.users.clear();

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    // 검증을 건너뛰면 응답 속도만으로 계정 존재가 드러난다.
    assertThat(this.hasher.matchesCallCount()).isEqualTo(1);
    assertThat(this.users.failures).isEmpty();
  }

  @Test
  void 미끼_해시는_요청마다_다시_만들지_않는다() {
    this.users.clear();
    int hashedBefore = this.hasher.hashCallCount();

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);

    // 매 요청 새로 만들면 Argon2id 비용을 두 번 치른다. 미끼는 프로세스당 하나면 충분하다.
    assertThat(this.hasher.hashCallCount()).isEqualTo(hashedBefore);
    assertThat(this.hasher.matchesCallCount()).isEqualTo(2);
  }

  @Test
  void 비밀번호가_틀리면_401이고_실패를_기록한다() {
    this.hasher.willReturnMatches(false);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    assertThat(this.users.failures)
        .containsExactly(
            new Failure(USER_ID, User.MAX_FAILED_LOGIN_ATTEMPTS, NOW_UTC.plusMinutes(10)));
    assertThat(this.users.resets).isEmpty();
  }

  @Test
  void 탈퇴_계정은_401이고_실패를_기록하지_않는다() {
    User withdrawn = activeVerifiedUser();
    withdrawn.withdraw(
        "withdrawn-1", Email.of("withdrawn-1@example.invalid"), "discarded$hash", NOW_UTC);
    this.users.register(withdrawn);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    assertThat(this.users.failures).isEmpty();
  }

  @Test
  void 잠긴_계정은_401이고_잠금_시각을_연장하지_않는다() {
    User locked = activeVerifiedUser();
    assign(locked, "lockedUntil", NOW_UTC.plusMinutes(5));
    this.users.register(locked);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    assertThat(this.users.failures).isEmpty();
  }

  @Test
  void 잠긴_계정에_틀린_비밀번호면_기록을_시도하되_조건이_막는다() {
    User locked = activeVerifiedUser();
    assign(locked, "lockedUntil", NOW_UTC.plusMinutes(5));
    this.users.register(locked);
    this.hasher.willReturnMatches(false);
    this.users.willRecordFailure(0);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    // 0행은 장애가 아니라 "이미 잠겨 있다"는 결정적 결과다. 503으로 바꾸지 않는다.
    assertThat(this.users.failures).hasSize(1);
  }

  // ---------- 이메일 미인증 (403) ----------

  @Test
  void 비밀번호가_맞고_이메일_미인증이면_403이다() {
    User unverified =
        User.create(USERNAME, STORED_HASH, UserRole.LEARNER, Email.of("a@example.com"), NOW_UTC);
    assign(unverified, "id", USER_ID);
    this.users.register(unverified);

    assertThat(errorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
    // 비밀번호 실패가 아니므로 카운터를 올리지 않는다.
    assertThat(this.users.failures).isEmpty();
    assertThat(this.users.resets).isEmpty();
  }

  @Test
  void 비밀번호가_틀린_미인증_계정은_401이다() {
    User unverified =
        User.create(USERNAME, STORED_HASH, UserRole.LEARNER, Email.of("a@example.com"), NOW_UTC);
    assign(unverified, "id", USER_ID);
    this.users.register(unverified);
    this.hasher.willReturnMatches(false);

    // 이메일 상태를 알려 주기 전에 자격 증명부터 통과해야 한다.
    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
  }

  // ---------- 저장소 장애 (503) ----------

  @Test
  void 사용자_조회_장애는_401이_아니라_503이다() {
    this.users.willFailLookup(new QueryTimeoutException("응답 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.hasher.matchesCallCount()).isZero();
  }

  @Test
  void 실패_카운터를_기록하지_못하면_401이_아니라_503이다() {
    this.hasher.willReturnMatches(false);
    this.transaction.willFail(new QueryTimeoutException("응답 없음"));

    // 카운터를 잃은 채 401만 주면 실패를 반복해 잠금을 우회할 수 있다 (ERD 3.1절).
    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  void 성공_초기화_commit_실패는_503이고_토큰을_만들지_않는다() {
    this.transaction.willFailAfterAction(new TransactionSystemException("commit 실패"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.accessTokens.issued).isEmpty();
    assertThat(this.store.issued).isEmpty();
  }

  // ---------- Snapshot 경합 ----------

  @Test
  void 초기화가_0행이면_재조회해_최신_row_version으로_한_번_재시도한다() {
    this.users.willResetFailures(0, 1);
    User moved = activeVerifiedUser();
    assign(moved, "rowVersion", SNAPSHOT_ROW_VERSION + 5);
    this.users.registerOnReread(moved);

    assertThatCode(this::login).doesNotThrowAnyException();

    assertThat(this.users.resets)
        .containsExactly(
            new Reset(USER_ID, SNAPSHOT_ROW_VERSION, SNAPSHOT_AUTH_VERSION),
            new Reset(USER_ID, SNAPSHOT_ROW_VERSION + 5, SNAPSHOT_AUTH_VERSION));
    // 같은 password_hash가 확인된 재시도에서 Argon2id를 다시 실행하지 않는다.
    assertThat(this.hasher.matchesCallCount()).isEqualTo(1);
  }

  @Test
  void 경합_재시도_뒤에는_재조회한_사용자로_토큰과_응답을_만든다() {
    this.users.willResetFailures(0, 1);
    User moved = activeVerifiedUser();
    assign(moved, "rowVersion", SNAPSHOT_ROW_VERSION + 5);
    assign(moved, "role", UserRole.INSTRUCTOR);
    moved.changeLevel("L4", NOW_UTC);
    this.users.registerOnReread(moved);

    AuthSession session = login();

    // 갱신에 실제로 성공한 상태가 응답과 토큰의 근거다. 오래된 Snapshot을 쓰면 바뀐 값이 그대로 나간다.
    assertThat(this.accessTokens.claims.get(0).role()).isEqualTo(UserRole.INSTRUCTOR);
    assertThat(session.user().role()).isEqualTo(UserRole.INSTRUCTOR);
    assertThat(session.user().level()).isEqualTo("L4");
  }

  @Test
  void 재시도해도_authVersion은_최초_Snapshot_값이다() {
    this.users.willResetFailures(0, 1);
    User moved = activeVerifiedUser();
    assign(moved, "rowVersion", SNAPSHOT_ROW_VERSION + 5);
    this.users.registerOnReread(moved);

    login();

    // 재조회 값이 같음을 확인한 뒤에만 진행하므로 결과는 같지만, 근거는 언제나 최초 Snapshot이다.
    assertThat(this.accessTokens.claims.get(0).authVersion()).isEqualTo(SNAPSHOT_AUTH_VERSION);
    assertThat(this.refreshTokens.claims.get(0).authVersion()).isEqualTo(SNAPSHOT_AUTH_VERSION);
  }

  @Test
  void 두_번째_초기화도_0행이면_503이고_세_번째는_없다() {
    this.users.willResetFailures(0, 0);
    User moved = activeVerifiedUser();
    assign(moved, "rowVersion", SNAPSHOT_ROW_VERSION + 5);
    this.users.registerOnReread(moved);

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.users.resets).hasSize(2);
    assertThat(this.accessTokens.issued).isEmpty();
  }

  @Test
  void 재조회에서_password_hash가_바뀌었으면_401이다() {
    this.users.willResetFailures(0);
    User changed = activeVerifiedUser();
    assign(changed, "passwordHash", "$argon2id$rotated");
    this.users.registerOnReread(changed);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    assertThat(this.accessTokens.issued).isEmpty();
  }

  @Test
  void 재조회에서_auth_version이_바뀌었으면_401이다() {
    this.users.willResetFailures(0);
    User changed = activeVerifiedUser();
    assign(changed, "authVersion", SNAPSHOT_AUTH_VERSION + 1);
    this.users.registerOnReread(changed);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void 재조회에서_탈퇴했으면_401이다() {
    this.users.willResetFailures(0);
    User withdrawn = activeVerifiedUser();
    withdrawn.withdraw(
        "withdrawn-1", Email.of("withdrawn-1@example.invalid"), "discarded$hash", NOW_UTC);
    this.users.registerOnReread(withdrawn);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void 재조회에서_이메일_인증이_풀렸으면_403이다() {
    this.users.willResetFailures(0);
    User unverified =
        User.create(USERNAME, STORED_HASH, UserRole.LEARNER, Email.of("a@example.com"), NOW_UTC);
    assign(unverified, "id", USER_ID);
    assign(unverified, "authVersion", SNAPSHOT_AUTH_VERSION);
    this.users.registerOnReread(unverified);

    assertThat(errorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
  }

  @Test
  void 재조회_장애는_503이다() {
    this.users.willResetFailures(0);
    this.users.willFailReread(new CannotCreateTransactionException("연결 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  void 재조회에서_사용자가_사라졌으면_401이다() {
    this.users.willResetFailures(0);
    this.users.clearOnReread();

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
  }

  // ---------- Redis 등록 ----------

  @Test
  void Redis_결과_불확실이면_503이고_토큰을_돌려주지_않는다() {
    this.store.willReturn(RefreshTokenStore.FamilyIssueResult.UNAVAILABLE);

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    // 적용 여부를 알 수 없으므로 재시도하지 않는다.
    assertThat(this.store.attempts).isEqualTo(1);
  }

  @Test
  void 식별자가_이미_있으면_새_familyId로_한_번_재시도한다() {
    this.store.willReturn(
        RefreshTokenStore.FamilyIssueResult.ALREADY_EXISTS,
        RefreshTokenStore.FamilyIssueResult.ISSUED);

    assertThatCode(this::login).doesNotThrowAnyException();

    assertThat(this.store.attempts).isEqualTo(2);
    assertThat(this.refreshTokens.claims.get(0).familyId())
        .isNotEqualTo(this.refreshTokens.claims.get(1).familyId());
  }

  @Test
  void 두_번째_충돌은_성공으로_만들지_않고_503이다() {
    this.store.willReturn(
        RefreshTokenStore.FamilyIssueResult.ALREADY_EXISTS,
        RefreshTokenStore.FamilyIssueResult.ALREADY_EXISTS);

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.store.attempts).isEqualTo(2);
  }

  @Test
  void Redis_실패_뒤에도_DB_초기화를_되돌리지_않는다() {
    this.store.willReturn(RefreshTokenStore.FamilyIssueResult.UNAVAILABLE);

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    // 분산 트랜잭션을 추가하지 않는다. 초기화는 이미 커밋된 채로 남는다 (3.6.2절).
    assertThat(this.users.resets).hasSize(1);
  }

  // ---------- 노출 ----------

  @Test
  void 결과와_명령의_toString에_값이_없다() {
    AuthSession session = login();

    assertThat(session).hasToString("AuthSession[REDACTED]");
    assertThat(new LoginService.Command(USERNAME, PASSWORD)).hasToString("LoginCommand[REDACTED]");
    assertThat(session.toString()).doesNotContain(session.accessToken()).doesNotContain(PASSWORD);
  }

  @Test
  void 오류_message에_아이디도_비밀번호도_없다() {
    this.hasher.willReturnMatches(false);

    BusinessException thrown = catchThrowableOfType(BusinessException.class, this::login);

    assertThat(thrown.getErrorCode().getMessage())
        .doesNotContain(USERNAME)
        .doesNotContain(PASSWORD)
        .doesNotContain(STORED_HASH);
    assertThat(thrown.getCause()).isNull();
  }

  // ---------- 도우미 ----------

  private record Failure(long userId, int maxAttempts, LocalDateTime lockedUntil) {}

  private record Reset(long userId, long expectedRowVersion, long expectedAuthVersion) {}

  private final class RecordingUserRepository implements UserRepository {

    private final List<Failure> failures = new ArrayList<>();
    private final List<Reset> resets = new ArrayList<>();
    private final List<String> lookedUpUsernames = new ArrayList<>();
    private final List<Integer> resetResults = new ArrayList<>();

    private User user;
    private User rereadUser;
    private boolean rereadRegistered;
    private RuntimeException lookupFailure;
    private RuntimeException rereadFailure;
    private int failureResult = 1;
    private int lookups;

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

    void willFailLookup(RuntimeException failure) {
      this.lookupFailure = failure;
    }

    void willFailReread(RuntimeException failure) {
      this.rereadFailure = failure;
    }

    void willRecordFailure(int result) {
      this.failureResult = result;
    }

    void willResetFailures(int... results) {
      this.resetResults.clear();
      for (int result : results) {
        this.resetResults.add(result);
      }
    }

    @Override
    public Optional<User> findByUsername(String username) {
      this.lookups++;
      this.lookedUpUsernames.add(username);
      if (this.lookupFailure != null) {
        throw this.lookupFailure;
      }
      return Optional.ofNullable(this.user);
    }

    @Override
    public Optional<User> findById(Long id) {
      if (this.rereadFailure != null) {
        throw this.rereadFailure;
      }
      return this.rereadRegistered
          ? Optional.ofNullable(this.rereadUser)
          : Optional.ofNullable(this.user);
    }

    @Override
    public int recordLoginFailure(
        long userId, int maxAttempts, LocalDateTime lockedUntil, LocalDateTime now) {
      this.failures.add(new Failure(userId, maxAttempts, lockedUntil));
      return this.failureResult;
    }

    @Override
    public int recordLoginSuccess(
        long userId, long expectedRowVersion, long expectedAuthVersion, LocalDateTime now) {
      this.resets.add(new Reset(userId, expectedRowVersion, expectedAuthVersion));
      int index = this.resets.size() - 1;
      return index < this.resetResults.size() ? this.resetResults.get(index) : 0;
    }

    @Override
    public User save(User user) {
      throw new UnsupportedOperationException("로그인은 엔티티를 저장하지 않는다.");
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

  private final class RecordingAccessTokenIssuer implements AccessTokenIssuer {

    private final List<AccessTokenIssuer.IssuedToken> issued = new ArrayList<>();
    private final List<AccessTokenIssuer.Claims> claims = new ArrayList<>();
    private final List<Integer> resetCountWhenIssued = new ArrayList<>();

    @Override
    public AccessTokenIssuer.IssuedToken issue(AccessTokenIssuer.Claims claims) {
      this.resetCountWhenIssued.add(LoginServiceTest.this.users.resets.size());
      this.claims.add(claims);
      AccessTokenIssuer.IssuedToken token =
          new AccessTokenIssuer.IssuedToken(
              "access." + this.issued.size() + ".signature",
              NOW.getEpochSecond(),
              NOW.getEpochSecond() + 900);
      this.issued.add(token);
      return token;
    }
  }

  private static final class RecordingRefreshTokenIssuer implements RefreshTokenIssuer {

    private final List<RefreshTokenIssuer.IssuedToken> issued = new ArrayList<>();
    private final List<RefreshTokenIssuer.Claims> claims = new ArrayList<>();

    @Override
    public RefreshTokenIssuer.IssuedToken issue(RefreshTokenIssuer.Claims claims) {
      this.claims.add(claims);
      RefreshTokenIssuer.IssuedToken token =
          new RefreshTokenIssuer.IssuedToken(
              "refresh." + this.issued.size() + ".signature",
              "h".repeat(43 - Integer.toString(this.issued.size()).length()) + this.issued.size(),
              UUID.randomUUID().toString(),
              claims.familyId(),
              NOW.getEpochSecond(),
              NOW.getEpochSecond() + 1_209_600);
      this.issued.add(token);
      return token;
    }
  }

  private static final class RecordingRefreshTokenStore implements RefreshTokenStore {

    private final List<RefreshTokenStore.TokenRecord> issued = new ArrayList<>();
    private final List<RefreshTokenStore.FamilyIssueResult> results = new ArrayList<>();
    private int attempts;

    void willReturn(RefreshTokenStore.FamilyIssueResult... results) {
      this.results.clear();
      this.results.addAll(List.of(results));
    }

    @Override
    public RefreshTokenStore.FamilyIssueResult issueFamily(
        RefreshTokenStore.TokenRecord firstToken) {
      RefreshTokenStore.FamilyIssueResult result =
          this.attempts < this.results.size()
              ? this.results.get(this.attempts)
              : RefreshTokenStore.FamilyIssueResult.ISSUED;
      this.attempts++;
      if (result == RefreshTokenStore.FamilyIssueResult.ISSUED) {
        this.issued.add(firstToken);
      }
      return result;
    }

    @Override
    public RefreshTokenStore.LookupResult lookup(RefreshTokenStore.TokenRecord submitted) {
      throw new UnsupportedOperationException("로그인은 사전 조회를 하지 않는다.");
    }

    @Override
    public RefreshTokenStore.RotationResult rotate(
        RefreshTokenStore.TokenRecord current, RefreshTokenStore.TokenRecord next) {
      throw new UnsupportedOperationException("로그인은 회전하지 않는다.");
    }

    @Override
    public RefreshTokenStore.FamilyRevocationResult revokeFamily(long userId, String familyId) {
      throw new UnsupportedOperationException("로그인은 폐기하지 않는다.");
    }
  }

  /** 트랜잭션 경계를 흉내 낸다. {@code execute}가 반환되기 직전이 commit 자리다. */
  private static final class RecordingTransactionOperations implements TransactionOperations {

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
