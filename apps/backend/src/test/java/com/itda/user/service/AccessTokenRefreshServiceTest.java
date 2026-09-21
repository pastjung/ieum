package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.problem.domain.Level;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 갱신의 판정과 순서를 저장소 없이 고정한다 (API 명세 3.3.1·3.6.1·3.6.2절).
 *
 * <p>여기서 확인하는 것은 "어떤 결과를 어떤 code로 돌려주고, 어떤 순서로 저장소를 건드리는가"다. Lua의 원자성과 실제 회전은 가짜 구현으로 증명되지 않으므로
 * Redis·MariaDB 테스트가 맡는다.
 */
class AccessTokenRefreshServiceTest {

  private static final long USER_ID = 42L;
  private static final long AUTH_VERSION = 7L;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 9, 0);

  private String familyId;
  private String jti;
  private String tokenHash;

  private RecordingVerifier verifier;
  private RecordingStore store;
  private RecordingRefreshIssuer refreshTokens;
  private RecordingAccessIssuer accessTokens;
  private RecordingUserRepository users;
  private AccessTokenRefreshService service;

  @BeforeEach
  void setUp() {
    this.familyId = UUID.randomUUID().toString();
    this.jti = UUID.randomUUID().toString();
    this.tokenHash = hashOf(1);
    this.verifier = new RecordingVerifier(verified());
    this.store = new RecordingStore();
    this.refreshTokens = new RecordingRefreshIssuer();
    this.accessTokens = new RecordingAccessIssuer();
    this.users = new RecordingUserRepository(activeVerifiedUser());
    this.service =
        new AccessTokenRefreshService(
            this.verifier, this.store, this.refreshTokens, this.accessTokens, this.users);
    this.store.willLookup(RefreshTokenStore.LookupResult.ACTIVE);
    this.store.willRotate(RefreshTokenStore.RotationResult.ROTATED);
  }

  /** 43자 Base64URL. 저장 계약이 요구하는 형식이면 되고 값 자체에는 의미가 없다. */
  private static String hashOf(int seed) {
    String suffix = Integer.toString(seed);
    return "h".repeat(43 - suffix.length()) + suffix;
  }

  private RefreshTokenVerifier.Verification.Verified verified() {
    return new RefreshTokenVerifier.Verification.Verified(
        USER_ID, this.familyId, this.jti, this.tokenHash, AUTH_VERSION, 1_000L, 2_000L);
  }

  private static User activeVerifiedUser() {
    User user =
        User.create(
            "learner01", "$argon2id$stored", UserRole.LEARNER, Email.of("a@example.com"), NOW);
    user.verifyEmail(NOW);
    assign(user, "id", USER_ID);
    assign(user, "authVersion", AUTH_VERSION);
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

  private AuthSession refresh() {
    return this.service.refresh(new AccessTokenRefreshService.Command("refresh.compact.value"));
  }

  private ErrorCode errorCode() {
    return catchThrowableOfType(BusinessException.class, this::refresh).getErrorCode();
  }

  // ---------- 성공 ----------

  @Test
  void 회전에_성공하면_새_세션을_돌려준다() {
    AuthSession session = refresh();

    assertThat(session.accessToken()).isEqualTo(this.accessTokens.issued.get(0).compactToken());
    assertThat(session.refreshToken()).isEqualTo(this.refreshTokens.issued.get(0).compactToken());
    assertThat(session.user().userId()).isEqualTo(USER_ID);
  }

  @Test
  void 제출한_토큰의_검증된_해시로_저장소를_조회하고_회전한다() {
    refresh();

    // 유스케이스가 원문을 해시하지 않는다. 검증기가 실어 준 값을 그대로 쓴다.
    assertThat(this.store.lookedUp.get(0).tokenHash()).isEqualTo(this.tokenHash);
    assertThat(this.store.rotatedFrom.get(0).tokenHash()).isEqualTo(this.tokenHash);
    assertThat(this.store.rotatedFrom.get(0).jti()).isEqualTo(this.jti);
    assertThat(this.store.rotatedFrom.get(0).familyId()).isEqualTo(this.familyId);
  }

  @Test
  void 신규_토큰은_같은_계열의_새_jti다() {
    refresh();

    RefreshTokenStore.TokenRecord next = this.store.rotatedTo.get(0);
    assertThat(next.familyId()).isEqualTo(this.familyId);
    assertThat(next.jti()).isNotEqualTo(this.jti);
    assertThat(next.tokenHash()).isEqualTo(this.refreshTokens.issued.get(0).tokenHash());
  }

  @Test
  void 신규_토큰의_세대는_초기_Snapshot에서_확인한_값이다() {
    refresh();

    // 회전 도중 올라간 최신 값을 붙이면 이미 무효가 된 자격으로 새 세대의 세션이 만들어진다.
    assertThat(this.refreshTokens.claims.get(0).authVersion()).isEqualTo(AUTH_VERSION);
    assertThat(this.accessTokens.claims.get(0).authVersion()).isEqualTo(AUTH_VERSION);
  }

  @Test
  void 만료_시각은_발급기가_돌려준_값_그대로다() {
    AuthSession session = refresh();

    AccessTokenIssuer.IssuedToken access = this.accessTokens.issued.get(0);
    RefreshTokenIssuer.IssuedToken next = this.refreshTokens.issued.get(0);
    assertThat(session.accessTokenExpiresAt().toEpochSecond(java.time.ZoneOffset.UTC))
        .isEqualTo(access.expiresAtEpochSecond());
    assertThat(session.refreshTokenExpiresAt().toEpochSecond(java.time.ZoneOffset.UTC))
        .isEqualTo(next.expiresAtEpochSecond());
  }

  @Test
  void 검사_순서는_Redis_조회_뒤_DB_조회_뒤_회전이다() {
    refresh();

    // DB 검사는 두 번이고 합치지 않는다 (3.6.2절).
    assertThat(this.store.lookups).isEqualTo(1);
    assertThat(this.users.lookups).isEqualTo(2);
    assertThat(this.store.rotations).isEqualTo(1);
    assertThat(this.users.lookupBeforeFirstRotation).isEqualTo(1);
  }

  // ---------- JWT 검증 ----------

  @Test
  void JWT가_무효면_401이고_저장소를_건드리지_않는다() {
    this.verifier.willReject();

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.lookups).isZero();
    assertThat(this.users.lookups).isZero();
  }

  // ---------- Redis lookup ----------

  @ParameterizedTest
  @EnumSource(
      value = RefreshTokenStore.LookupResult.class,
      names = {"NOT_FOUND", "HASH_MISMATCH", "FAMILY_REVOKED"})
  void 결정적으로_무효인_조회_결과는_401이고_회전하지_않는다(RefreshTokenStore.LookupResult lookup) {
    this.store.willLookup(lookup);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.rotations).isZero();
    assertThat(this.users.lookups).isZero();
  }

  @Test
  void 조회_장애는_401이_아니라_503이다() {
    this.store.willLookup(RefreshTokenStore.LookupResult.UNAVAILABLE);

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.store.rotations).isZero();
  }

  @ParameterizedTest
  @EnumSource(
      value = RefreshTokenStore.LookupResult.class,
      names = {"ROTATED", "REVOKED", "MISMATCHED"})
  void 재사용_의심_상태는_원자_회전으로_최종_판정한다(RefreshTokenStore.LookupResult lookup) {
    this.store.willLookup(lookup);
    this.store.willRotate(RefreshTokenStore.RotationResult.REUSED);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    // lookup만으로 끝내면 탐지와 계열 폐기가 일어나는 원자 연산까지 가지 못한다.
    assertThat(this.store.rotations).isEqualTo(1);
    // 사용자 상태는 이 판정에 쓰이지 않으므로 DB를 읽지 않는다.
    assertThat(this.users.lookups).isZero();
  }

  @ParameterizedTest
  @EnumSource(
      value = RefreshTokenStore.LookupResult.class,
      names = {"ROTATED", "REVOKED", "MISMATCHED"})
  void 재사용_확인_회전이_실패해도_새_토큰은_나가지_않는다(RefreshTokenStore.LookupResult lookup) {
    this.store.willLookup(lookup);
    this.store.willRotate(RefreshTokenStore.RotationResult.NOT_FOUND);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.accessTokens.issued).isEmpty();
  }

  @Test
  void 재사용_확인_회전의_장애는_503이다() {
    this.store.willLookup(RefreshTokenStore.LookupResult.ROTATED);
    this.store.willRotate(RefreshTokenStore.RotationResult.UNAVAILABLE);

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  // ---------- 초기 MariaDB ----------

  @Test
  void 사용자가_없으면_401이고_회전하지_않는다() {
    this.users.clear();

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.rotations).isZero();
  }

  @Test
  void 탈퇴한_사용자는_401이다() {
    User withdrawn = activeVerifiedUser();
    withdrawn.withdraw(
        "withdrawn-1", Email.of("withdrawn-1@example.invalid"), "discarded$hash", NOW);
    this.users.register(withdrawn);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.rotations).isZero();
  }

  @Test
  void authVersion이_다르면_401이다() {
    User rotatedAuth = activeVerifiedUser();
    assign(rotatedAuth, "authVersion", AUTH_VERSION + 1);
    this.users.register(rotatedAuth);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.rotations).isZero();
  }

  @Test
  void 이메일_미인증은_403이다() {
    User unverified =
        User.create(
            "learner01", "$argon2id$stored", UserRole.LEARNER, Email.of("a@example.com"), NOW);
    assign(unverified, "id", USER_ID);
    assign(unverified, "authVersion", AUTH_VERSION);
    this.users.register(unverified);

    assertThat(errorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
    assertThat(this.store.rotations).isZero();
  }

  @Test
  void 탈퇴와_세대_불일치가_이메일_판정보다_먼저다() {
    User withdrawn = activeVerifiedUser();
    withdrawn.withdraw(
        "withdrawn-2", Email.of("withdrawn-2@example.invalid"), "discarded$hash", NOW);
    this.users.register(withdrawn);

    // 탈퇴는 email_verified_at도 비우므로, 순서가 바뀌면 탈퇴 계정이 403을 받아 상태가 드러난다.
    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
  }

  @Test
  void 초기_조회_장애는_503이고_회전하지_않는다() {
    this.users.willFail(new QueryTimeoutException("응답 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.store.rotations).isZero();
  }

  // ---------- 원자 회전 ----------

  @ParameterizedTest
  @EnumSource(
      value = RefreshTokenStore.RotationResult.class,
      names = {"REUSED", "NOT_FOUND", "HASH_MISMATCH", "FAMILY_REVOKED"})
  void 결정적_회전_실패는_401이고_토큰을_돌려주지_않는다(RefreshTokenStore.RotationResult rotation) {
    this.store.willRotate(rotation);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.accessTokens.issued).isEmpty();
  }

  @Test
  void 회전_장애는_503이고_토큰을_돌려주지_않는다() {
    this.store.willRotate(RefreshTokenStore.RotationResult.UNAVAILABLE);

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.accessTokens.issued).isEmpty();
  }

  @Test
  void 식별자가_이미_있으면_새_jti로_한_번_재시도한다() {
    this.store.willRotate(
        RefreshTokenStore.RotationResult.ALREADY_EXISTS, RefreshTokenStore.RotationResult.ROTATED);

    assertThatCode(this::refresh).doesNotThrowAnyException();

    assertThat(this.store.rotations).isEqualTo(2);
    assertThat(this.store.rotatedTo.get(0).jti()).isNotEqualTo(this.store.rotatedTo.get(1).jti());
  }

  @Test
  void 두_번째_충돌은_503이고_세_번째_시도가_없다() {
    this.store.willRotate(
        RefreshTokenStore.RotationResult.ALREADY_EXISTS,
        RefreshTokenStore.RotationResult.ALREADY_EXISTS);

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.store.rotations).isEqualTo(2);
    assertThat(this.refreshTokens.issued).hasSize(2);
    assertThat(this.accessTokens.issued).isEmpty();
  }

  // ---------- 회전 후 재확인 ----------

  @Test
  void 재확인에서_사용자가_사라졌으면_계열을_폐기하고_401이다() {
    this.users.clearOnSecondLookup();

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.revokedFamilies).containsExactly(this.familyId);
    assertThat(this.accessTokens.issued).isEmpty();
  }

  @Test
  void 재확인에서_탈퇴했으면_계열을_폐기하고_401이다() {
    User withdrawn = activeVerifiedUser();
    withdrawn.withdraw(
        "withdrawn-3", Email.of("withdrawn-3@example.invalid"), "discarded$hash", NOW);
    this.users.registerOnSecondLookup(withdrawn);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.revokedFamilies).containsExactly(this.familyId);
  }

  @Test
  void 재확인에서_세대가_올라갔으면_계열을_폐기하고_401이다() {
    User rotatedAuth = activeVerifiedUser();
    assign(rotatedAuth, "authVersion", AUTH_VERSION + 1);
    this.users.registerOnSecondLookup(rotatedAuth);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.revokedFamilies).containsExactly(this.familyId);
    assertThat(this.accessTokens.issued).isEmpty();
  }

  @Test
  void 재확인_장애는_계열_폐기를_시도하고_503이다() {
    this.users.willFailSecondLookup(new CannotCreateTransactionException("연결 없음"));

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.store.revokedFamilies).containsExactly(this.familyId);
    assertThat(this.accessTokens.issued).isEmpty();
  }

  @Test
  void 계열_폐기가_실패해도_토큰을_돌려주지_않는다() {
    this.users.clearOnSecondLookup();
    this.store.willRevoke(RefreshTokenStore.FamilyRevocationResult.UNAVAILABLE);

    // 폐기 실패를 다른 오류로 승격하지도 않는다. 무효화 권위는 DB에 있다 (3.6.2절).
    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.accessTokens.issued).isEmpty();
  }

  @Test
  void 재확인에_성공하면_재조회한_사용자로_응답과_Access를_만든다() {
    User promoted = activeVerifiedUser();
    assign(promoted, "role", UserRole.INSTRUCTOR);
    promoted.changeLevel(Level.SENTENCE, NOW);
    this.users.registerOnSecondLookup(promoted);

    AuthSession session = refresh();

    assertThat(this.accessTokens.claims.get(0).role()).isEqualTo(UserRole.INSTRUCTOR);
    assertThat(session.user().role()).isEqualTo(UserRole.INSTRUCTOR);
    assertThat(session.user().level()).isEqualTo(Level.SENTENCE);
    // 세대는 여전히 초기 Snapshot 값이다.
    assertThat(this.accessTokens.claims.get(0).authVersion()).isEqualTo(AUTH_VERSION);
  }

  @Test
  void 성공_경로에서는_계열을_폐기하지_않는다() {
    refresh();

    assertThat(this.store.revokedFamilies).isEmpty();
  }

  // ---------- 노출 ----------

  @Test
  void 입력과_결과의_toString에_토큰이_없다() {
    AuthSession session = refresh();

    assertThat(new AccessTokenRefreshService.Command("refresh.compact.value"))
        .hasToString("AccessTokenRefreshCommand[REDACTED]");
    assertThat(session).hasToString("AuthSession[REDACTED]");
    assertThat(session.toString()).doesNotContain(session.accessToken());
  }

  @Test
  void 오류_message에_토큰도_해시도_식별자도_없다() {
    this.store.willLookup(RefreshTokenStore.LookupResult.NOT_FOUND);

    BusinessException thrown = catchThrowableOfType(BusinessException.class, this::refresh);

    assertThat(thrown.getErrorCode().getMessage())
        .doesNotContain(this.tokenHash)
        .doesNotContain(this.jti)
        .doesNotContain(this.familyId);
    assertThat(thrown.getCause()).isNull();
  }

  // ---------- 도우미 ----------

  private static final class RecordingVerifier implements RefreshTokenVerifier {

    private final RefreshTokenVerifier.Verification.Verified verified;
    private boolean reject;

    private RecordingVerifier(RefreshTokenVerifier.Verification.Verified verified) {
      this.verified = verified;
    }

    void willReject() {
      this.reject = true;
    }

    @Override
    public RefreshTokenVerifier.Verification verify(String compactToken) {
      return this.reject ? new RefreshTokenVerifier.Verification.Invalid() : this.verified;
    }
  }

  private static final class RecordingStore implements RefreshTokenStore {

    private final List<RefreshTokenStore.TokenRecord> lookedUp = new ArrayList<>();
    private final List<RefreshTokenStore.TokenRecord> rotatedFrom = new ArrayList<>();
    private final List<RefreshTokenStore.TokenRecord> rotatedTo = new ArrayList<>();
    private final List<String> revokedFamilies = new ArrayList<>();
    private final List<RefreshTokenStore.RotationResult> rotationResults = new ArrayList<>();

    private RefreshTokenStore.LookupResult lookupResult = RefreshTokenStore.LookupResult.ACTIVE;
    private RefreshTokenStore.FamilyRevocationResult revocationResult =
        RefreshTokenStore.FamilyRevocationResult.REVOKED;
    private int lookups;
    private int rotations;

    void willLookup(RefreshTokenStore.LookupResult result) {
      this.lookupResult = result;
    }

    void willRotate(RefreshTokenStore.RotationResult... results) {
      this.rotationResults.clear();
      this.rotationResults.addAll(List.of(results));
    }

    void willRevoke(RefreshTokenStore.FamilyRevocationResult result) {
      this.revocationResult = result;
    }

    @Override
    public RefreshTokenStore.LookupResult lookup(RefreshTokenStore.TokenRecord submitted) {
      this.lookups++;
      this.lookedUp.add(submitted);
      return this.lookupResult;
    }

    @Override
    public RefreshTokenStore.FamilyIssueResult issueFamily(
        RefreshTokenStore.TokenRecord firstToken) {
      throw new UnsupportedOperationException("갱신은 계열을 새로 만들지 않는다.");
    }

    @Override
    public RefreshTokenStore.RotationResult rotate(
        RefreshTokenStore.TokenRecord current, RefreshTokenStore.TokenRecord next) {
      this.rotatedFrom.add(current);
      this.rotatedTo.add(next);
      RefreshTokenStore.RotationResult result =
          this.rotations < this.rotationResults.size()
              ? this.rotationResults.get(this.rotations)
              : RefreshTokenStore.RotationResult.ROTATED;
      this.rotations++;
      return result;
    }

    @Override
    public RefreshTokenStore.FamilyRevocationResult revokeFamily(long userId, String familyId) {
      this.revokedFamilies.add(familyId);
      return this.revocationResult;
    }

    @Override
    public RefreshTokenStore.AllFamiliesRevocationResult revokeAllFamilies(long userId) {
      throw new UnsupportedOperationException();
    }
  }

  private final class RecordingRefreshIssuer implements RefreshTokenIssuer {

    private final List<RefreshTokenIssuer.IssuedToken> issued = new ArrayList<>();
    private final List<RefreshTokenIssuer.Claims> claims = new ArrayList<>();

    @Override
    public RefreshTokenIssuer.IssuedToken issue(RefreshTokenIssuer.Claims claims) {
      this.claims.add(claims);
      RefreshTokenIssuer.IssuedToken token =
          new RefreshTokenIssuer.IssuedToken(
              "refresh." + this.issued.size() + ".signature",
              hashOf(10 + this.issued.size()),
              UUID.randomUUID().toString(),
              claims.familyId(),
              3_000L,
              3_000L + 1_209_600L);
      this.issued.add(token);
      return token;
    }
  }

  private static final class RecordingAccessIssuer implements AccessTokenIssuer {

    private final List<AccessTokenIssuer.IssuedToken> issued = new ArrayList<>();
    private final List<AccessTokenIssuer.Claims> claims = new ArrayList<>();

    @Override
    public AccessTokenIssuer.IssuedToken issue(AccessTokenIssuer.Claims claims) {
      this.claims.add(claims);
      AccessTokenIssuer.IssuedToken token =
          new AccessTokenIssuer.IssuedToken(
              "access." + this.issued.size() + ".signature", 4_000L, 4_900L);
      this.issued.add(token);
      return token;
    }
  }

  private final class RecordingUserRepository implements UserRepository {

    private User user;
    private User secondLookupUser;
    private boolean secondLookupRegistered;
    private RuntimeException failure;
    private RuntimeException secondLookupFailure;
    private int lookups;
    private int lookupBeforeFirstRotation;

    private RecordingUserRepository(User user) {
      this.user = user;
    }

    void register(User user) {
      this.user = user;
    }

    void clear() {
      this.user = null;
    }

    void registerOnSecondLookup(User user) {
      this.secondLookupUser = user;
      this.secondLookupRegistered = true;
    }

    void clearOnSecondLookup() {
      this.secondLookupUser = null;
      this.secondLookupRegistered = true;
    }

    void willFail(RuntimeException failure) {
      this.failure = failure;
    }

    void willFailSecondLookup(RuntimeException failure) {
      this.secondLookupFailure = failure;
    }

    @Override
    public Optional<User> findById(Long id) {
      this.lookups++;
      if (AccessTokenRefreshServiceTest.this.store.rotations == 0) {
        this.lookupBeforeFirstRotation = this.lookups;
      }
      if (this.lookups == 1) {
        if (this.failure != null) {
          throw this.failure;
        }
        return Optional.ofNullable(this.user);
      }
      if (this.secondLookupFailure != null) {
        throw this.secondLookupFailure;
      }
      return this.secondLookupRegistered
          ? Optional.ofNullable(this.secondLookupUser)
          : Optional.ofNullable(this.user);
    }

    @Override
    public User save(User user) {
      throw new UnsupportedOperationException("갱신은 엔티티를 저장하지 않는다.");
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
    public int withdraw(
        long userId,
        long expectedRowVersion,
        String expectedPasswordHash,
        long expectedAuthVersion,
        String pseudonymUsername,
        Email pseudonymEmail,
        String discardedPasswordHash,
        LocalDateTime now) {
      throw new UnsupportedOperationException();
    }
  }
}
