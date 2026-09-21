package com.itda.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.itda.global.error.GlobalExceptionHandler;
import com.itda.global.security.AccessJwtProperties;
import com.itda.global.web.RequestTracingFilter;
import com.itda.support.log.CapturingAppender;
import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.domain.Email;
import com.itda.user.domain.MailOutboxEntry;
import com.itda.user.domain.PasswordPolicy;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.ActionTokenStore;
import com.itda.user.repository.MailOutboxJpaRepository;
import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserRepository;
import com.itda.user.service.AccessTokenIssuer;
import com.itda.user.service.ActionKeyRing;
import com.itda.user.service.ActionTokenClaims;
import com.itda.user.service.CompromisedPasswordChecker;
import com.itda.user.service.EmailVerificationTokenIssuer;
import com.itda.user.service.IssuedActionToken;
import com.itda.user.service.NimbusPasswordResetTokenCodec;
import com.itda.user.service.PasswordHasher;
import com.itda.user.service.PasswordResetService;
import com.itda.user.service.PasswordResetTokenIssuer;
import com.itda.user.service.RefreshTokenIssuer;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 비밀번호 재설정 두 API의 HTTP 계약을 실제 Tomcat으로 검증한다 (API 명세 3.4.2·3.6.2절).
 *
 * <p>JWT·Argon2id·H2·Access/Refresh 검증은 운영과 같은 bean을 쓴다. Redis 상태와 외부 유출 조회만 대역이다 — Lua 원자성과 실제 상태
 * 전이는 실제 Redis 테스트가, 조건부 UPDATE 경합은 실제 MariaDB 테스트가 맡는다.
 */
@WebApiTest
class PasswordResetApiIntegrationTest {

  private static final String REQUESTS = "/api/v1/password-reset-requests";
  private static final String RESETS = "/api/v1/password-resets";
  private static final String ME = "/api/v1/users/me";
  private static final String ACCESS_TOKENS = "/api/v1/access-tokens";
  private static final String HEALTH = "/api/v1/health";

  private static final String OLD_PASSWORD = "충분히 긴 기존 비밀번호 문구";
  private static final String NEW_PASSWORD = "충분히 긴 새 비밀번호 문구";

  private static final AtomicLong SEQUENCE = new AtomicLong();

  /** RFC 4122 UUID v4. */
  private static final Pattern UUID_V4 =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  @LocalServerPort private int port;
  @Autowired private UserRepository userRepository;
  @Autowired private MailOutboxJpaRepository mailOutboxJpaRepository;
  @Autowired private PasswordResetTokenIssuer passwordResetTokenIssuer;
  @Autowired private EmailVerificationTokenIssuer emailVerificationTokenIssuer;
  @Autowired private AccessTokenIssuer accessTokenIssuer;
  @Autowired private RefreshTokenIssuer refreshTokenIssuer;
  @Autowired private PasswordHasher passwordHasher;
  @Autowired private ActionKeyRing actionKeyRing;
  @Autowired private AccessJwtProperties accessJwtProperties;

  /** Redis 상태만 대역이다. 나머지 경로는 운영과 같은 bean을 쓴다. */
  @MockitoBean private ActionTokenStore actionTokenStore;

  @MockitoBean private RefreshTokenStore refreshTokenStore;

  /** 외부 유출 조회 API를 테스트에서 호출하지 않는다. */
  @MockitoBean private CompromisedPasswordChecker compromisedPasswordChecker;

  private HttpTestClient client;
  private String email;

  /**
   * 대역이 적격으로 인정할 토큰 해시.
   *
   * <p>대역이 무조건 {@code ACTIVE}·{@code CONSUMED}를 돌려주면 "제출한 원문이 발급한 원문과 같은가"가 검증되지 않는다. 원문을 한 글자라도
   * 가공하면 실제 Redis처럼 {@code HASH_MISMATCH}가 된다.
   */
  private final Set<String> eligibleTokenHashes = ConcurrentHashMap.newKeySet();

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
    this.email = "resetter" + SEQUENCE.incrementAndGet() + "@example.test";
    this.eligibleTokenHashes.clear();
    when(this.actionTokenStore.lookup(any()))
        .thenAnswer(
            invocation ->
                isEligible(invocation.getArgument(0))
                    ? ActionTokenStore.LookupResult.ACTIVE
                    : ActionTokenStore.LookupResult.HASH_MISMATCH);
    when(this.actionTokenStore.consume(any()))
        .thenAnswer(
            invocation ->
                isEligible(invocation.getArgument(0))
                    ? ActionTokenStore.ConsumeResult.CONSUMED
                    : ActionTokenStore.ConsumeResult.HASH_MISMATCH);
    when(this.refreshTokenStore.revokeAllFamilies(anyLong()))
        .thenReturn(RefreshTokenStore.AllFamiliesRevocationResult.REVOKED);
    when(this.refreshTokenStore.lookup(any())).thenReturn(RefreshTokenStore.LookupResult.ACTIVE);
    when(this.refreshTokenStore.rotate(any(), any()))
        .thenReturn(RefreshTokenStore.RotationResult.ROTATED);
    when(this.compromisedPasswordChecker.check(any()))
        .thenReturn(CompromisedPasswordChecker.CheckResult.NOT_COMPROMISED);
  }

  private boolean isEligible(ActionTokenStore.TokenRecord record) {
    return this.eligibleTokenHashes.contains(record.tokenHash());
  }

  // ---------- 준비 ----------

  private IssuedActionToken issueToken(long userId) {
    IssuedActionToken token = this.passwordResetTokenIssuer.issue(new ActionTokenClaims(userId));
    this.eligibleTokenHashes.add(token.tokenHash());
    return token;
  }

  private long registerUser(boolean emailVerified, String password) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    User user =
        User.create(
            "resetter" + SEQUENCE.incrementAndGet(),
            this.passwordHasher.hash(PasswordPolicy.normalize(password)).value(),
            UserRole.LEARNER,
            Email.of(this.email),
            now);
    if (emailVerified) {
      user.verifyEmail(now);
    }
    return this.userRepository.save(user).getId();
  }

  private long registerUser(boolean emailVerified) {
    return registerUser(emailVerified, OLD_PASSWORD);
  }

  private void withdraw(long userId) {
    User user = reload(userId);
    user.withdraw(
        "withdrawn-" + userId,
        Email.of("withdrawn-" + userId + "@example.invalid"),
        "discarded$hash",
        LocalDateTime.now(ZoneOffset.UTC));
    this.userRepository.save(user);
  }

  private User reload(long userId) {
    return this.userRepository.findById(userId).orElseThrow();
  }

  private boolean passwordMatches(long userId, String password) {
    return this.passwordHasher.matches(
        PasswordPolicy.normalize(password), reload(userId).getPasswordHash());
  }

  // ---------- 요청 도우미 ----------

  private HttpResponse<String> post(String path, String body, String... extraHeaders) {
    String[] headers = new String[2 + extraHeaders.length];
    headers[0] = HttpHeaders.CONTENT_TYPE;
    headers[1] = "application/json";
    System.arraycopy(extraHeaders, 0, headers, 2, extraHeaders.length);
    return this.client.send("POST", path, body, headers);
  }

  private HttpResponse<String> request(String emailJson, String... extraHeaders) {
    return post(REQUESTS, "{\"email\": %s}".formatted(emailJson), extraHeaders);
  }

  private HttpResponse<String> reset(
      String tokenJson, String newPasswordJson, String... extraHeaders) {
    return post(
        RESETS,
        "{\"token\": %s, \"newPassword\": %s}".formatted(tokenJson, newPasswordJson),
        extraHeaders);
  }

  private HttpResponse<String> reset(IssuedActionToken token) {
    return reset(quoted(token.compactToken()), quoted(NEW_PASSWORD));
  }

  private static String quoted(String value) {
    return "\"" + value + "\"";
  }

  private static void assertErrorCode(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body).containsOnlyKeys("code", "message");
    assertThat(body).containsEntry("code", code);
    assertThat(response.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
    assertRequestId(response);
  }

  private static void assertRequestId(HttpResponse<String> response) {
    List<String> requestIds = response.headers().allValues("X-Request-Id");
    assertThat(requestIds).hasSize(1);
    assertThat(requestIds.get(0)).matches(UUID_V4);
    assertThat(UUID.fromString(requestIds.get(0)).version()).isEqualTo(4);
  }

  /** 성공은 완전히 빈 body다. 토큰·쿠키·위치를 내보내지 않는다. */
  private static void assertEmptySuccess(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(response.headers().firstValue(HttpHeaders.LOCATION)).isEmpty();
    assertThat(response.headers().firstValue(HttpHeaders.AUTHORIZATION)).isEmpty();
    assertRequestId(response);
  }

  // ---------- 요청 API: 성공 ----------

  @Test
  void 정상_요청은_202와_빈_body다() {
    assertEmptySuccess(request(quoted(this.email)), 202);
  }

  @Test
  void 요청은_PASSWORD_RESET_작업을_접수한다() {
    request(quoted(this.email));

    assertThat(this.mailOutboxJpaRepository.findAll())
        .filteredOn(entry -> this.email.equals(entry.getRecipientEmail()))
        .singleElement()
        .satisfies(entry -> assertThat(entry.getPurpose().name()).isEqualTo("PASSWORD_RESET"));
  }

  @Test
  void 요청은_trim하고_소문자로_정규화해_저장한다() {
    request(quoted("  " + this.email.toUpperCase() + "  "));

    assertThat(this.mailOutboxJpaRepository.findAll())
        .extracting(MailOutboxEntry::getRecipientEmail)
        .contains(this.email);
  }

  @Test
  void 계정_상태와_무관하게_같은_202를_돌려준다() {
    // 계정 없음
    HttpResponse<String> noAccount = request(quoted(this.email));

    // ACTIVE 계정
    this.email = "active" + SEQUENCE.incrementAndGet() + "@example.test";
    registerUser(true);
    HttpResponse<String> active = request(quoted(this.email));

    // 미인증 ACTIVE 계정
    this.email = "unverified" + SEQUENCE.incrementAndGet() + "@example.test";
    registerUser(false);
    HttpResponse<String> unverified = request(quoted(this.email));

    // 탈퇴 계정
    this.email = "withdrawn" + SEQUENCE.incrementAndGet() + "@example.test";
    withdraw(registerUser(true));
    HttpResponse<String> withdrawn = request(quoted(this.email));

    // 상태·body·명세된 헤더가 모두 같다. 응답만으로 계정 존재·상태를 알 수 없다 (3.4.2절).
    for (HttpResponse<String> response : List.of(noAccount, active, unverified, withdrawn)) {
      assertEmptySuccess(response, 202);
    }
    assertThat(
            List.of(noAccount, active, unverified, withdrawn).stream()
                .map(response -> response.headers().map().keySet())
                .distinct())
        .hasSize(1);
  }

  @Test
  void 요청은_X_Client_Platform을_읽지_않는다() {
    assertEmptySuccess(request(quoted(this.email), "X-Client-Platform", "NOT-A-PLATFORM"), 202);
  }

  // ---------- 요청 API: 입력 위반 ----------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"\"", // 빈 문자열
        "\"   \"", // 공백만
        "\"not-an-email\"",
        "\"learner@\"",
        "null", // JSON null
        "123", // 숫자
        "true", // 불리언
        "{}", // 객체
        "[]" // 배열
      })
  void 요청의_형식_위반은_422_INVALID_ACCOUNT_INPUT이다(String emailJson) {
    assertErrorCode(request(emailJson), 422, "INVALID_ACCOUNT_INPUT");
  }

  @Test
  void 요청_필드가_없으면_422다() {
    assertErrorCode(post(REQUESTS, "{}"), 422, "INVALID_ACCOUNT_INPUT");
  }

  @Test
  void 요청의_정규화_후_254자_경계를_지킨다() {
    String domain = "@example.test";
    String max = "a".repeat(254 - domain.length()) + domain;
    String over = "a".repeat(255 - domain.length()) + domain;

    assertEmptySuccess(request(quoted(max)), 202);
    assertErrorCode(request(quoted(over)), 422, "INVALID_ACCOUNT_INPUT");
  }

  @Test
  void 요청의_JSON_문법_오류는_400_MALFORMED_REQUEST_BODY다() {
    assertErrorCode(post(REQUESTS, "{\"email\": "), 400, "MALFORMED_REQUEST_BODY");
    assertErrorCode(post(REQUESTS, null), 400, "MALFORMED_REQUEST_BODY");
  }

  @Test
  void 요청_오류_응답에_입력값이_없다() {
    String rejected = "not-an-email-" + this.email;

    assertThat(request(quoted(rejected)).body())
        .doesNotContain(rejected)
        .doesNotContain(this.email);
  }

  // ---------- 완료 API: 성공 ----------

  @Test
  void 정상_토큰은_204와_빈_body이고_토큰도_쿠키도_발급하지_않는다() {
    long userId = registerUser(true);
    IssuedActionToken token = issueToken(userId);

    HttpResponse<String> response = reset(token);

    assertEmptySuccess(response, 204);
    assertThat(passwordMatches(userId, NEW_PASSWORD)).isTrue();
    assertThat(passwordMatches(userId, OLD_PASSWORD)).isFalse();
  }

  @Test
  void 성공하면_auth_version을_올리고_실패_횟수와_잠금을_풀며_email_verified_at을_유지한다() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    User user =
        User.create(
            "locked" + SEQUENCE.incrementAndGet(),
            this.passwordHasher.hash(PasswordPolicy.normalize(OLD_PASSWORD)).value(),
            UserRole.LEARNER,
            Email.of(this.email),
            now);
    user.verifyEmail(now);
    for (int i = 0; i < User.MAX_FAILED_LOGIN_ATTEMPTS; i++) {
      user.recordLoginFailure(now);
    }
    long userId = this.userRepository.save(user).getId();
    User before = reload(userId);
    assertThat(before.getLockedUntil()).isNotNull();

    assertEmptySuccess(reset(issueToken(userId)), 204);

    User after = reload(userId);
    assertThat(after.getAuthVersion()).isEqualTo(before.getAuthVersion() + 1);
    assertThat(after.getFailedLoginCount()).isZero();
    assertThat(after.getLockedUntil()).isNull();
    assertThat(after.getRowVersion()).isGreaterThan(before.getRowVersion());
    assertThat(after.getEmailVerifiedAt()).isEqualTo(before.getEmailVerifiedAt());
    assertThat(after.getStatus()).isEqualTo(before.getStatus());
    assertThat(after.getUsername()).isEqualTo(before.getUsername());
    assertThat(after.getEmail()).isEqualTo(before.getEmail());
    assertThat(after.getRole()).isEqualTo(before.getRole());
  }

  @Test
  void 미인증_계정도_재설정되고_email_verified_at은_NULL로_남는다() {
    long userId = registerUser(false);

    assertEmptySuccess(reset(issueToken(userId)), 204);

    assertThat(reload(userId).getEmailVerifiedAt()).isNull();
    assertThat(passwordMatches(userId, NEW_PASSWORD)).isTrue();
  }

  @Test
  void 기존과_같은_비밀번호도_정책을_통과하면_204다() {
    long userId = registerUser(true, NEW_PASSWORD);
    long authVersion = reload(userId).getAuthVersion();

    assertEmptySuccess(reset(issueToken(userId)), 204);

    assertThat(passwordMatches(userId, NEW_PASSWORD)).isTrue();
    assertThat(reload(userId).getAuthVersion()).isEqualTo(authVersion + 1);
  }

  @Test
  void 성공_뒤_기존_Access_Token은_보호_API에서_401이다() {
    long userId = registerUser(true);
    String accessToken =
        this.accessTokenIssuer
            .issue(new AccessTokenIssuer.Claims(userId, UserRole.LEARNER, 0))
            .compactToken();
    assertThat(this.client.get(ME, HttpHeaders.AUTHORIZATION, "Bearer " + accessToken).statusCode())
        .isEqualTo(200);

    assertEmptySuccess(reset(issueToken(userId)), 204);

    assertErrorCode(
        this.client.get(ME, HttpHeaders.AUTHORIZATION, "Bearer " + accessToken),
        401,
        "UNAUTHORIZED");
  }

  @Test
  void 성공_뒤_기존_Refresh_Token은_갱신에서_401이다() {
    long userId = registerUser(true);
    String refreshToken =
        this.refreshTokenIssuer
            .issue(new RefreshTokenIssuer.Claims(userId, UUID.randomUUID().toString(), 0))
            .compactToken();
    String refreshBody = "{\"refreshToken\": \"" + refreshToken + "\"}";
    assertThat(post(ACCESS_TOKENS, refreshBody, "X-Client-Platform", "MOBILE").statusCode())
        .isEqualTo(201);

    assertEmptySuccess(reset(issueToken(userId)), 204);

    // Redis 대역은 여전히 ACTIVE를 돌려준다. 거부 근거는 DB auth_version 비교뿐이다 (3.6.1절).
    assertErrorCode(
        post(ACCESS_TOKENS, refreshBody, "X-Client-Platform", "MOBILE"),
        401,
        "INVALID_REFRESH_TOKEN");
  }

  @Test
  void 성공하면_그_사용자의_Refresh_계열_정리를_요청한다() {
    long userId = registerUser(true);

    reset(issueToken(userId));

    verify(this.refreshTokenStore).revokeAllFamilies(userId);
  }

  @Test
  void 계열_정리가_끝나지_않아도_204다() {
    long userId = registerUser(true);
    when(this.refreshTokenStore.revokeAllFamilies(anyLong()))
        .thenReturn(RefreshTokenStore.AllFamiliesRevocationResult.UNAVAILABLE);

    assertEmptySuccess(reset(issueToken(userId)), 204);
    assertThat(passwordMatches(userId, NEW_PASSWORD)).isTrue();
  }

  @Test
  void 계열_정리가_예외로_끝나도_204다() {
    long userId = registerUser(true);
    doThrow(new IllegalStateException("정리 실패"))
        .when(this.refreshTokenStore)
        .revokeAllFamilies(anyLong());

    assertEmptySuccess(reset(issueToken(userId)), 204);
    assertThat(reload(userId).getAuthVersion()).isEqualTo(1);
  }

  @Test
  void 완료는_X_Client_Platform을_읽지_않는다() {
    long userId = registerUser(true);
    IssuedActionToken token = issueToken(userId);

    assertEmptySuccess(
        reset(
            quoted(token.compactToken()),
            quoted(NEW_PASSWORD),
            "X-Client-Platform",
            "NOT-A-PLATFORM"),
        204);
  }

  // ---------- 완료 API: 입력 위반 (422) ----------

  @ParameterizedTest
  @ValueSource(strings = {"\"\"", "\"   \"", "null", "123", "true", "{}", "[]"})
  void token의_형식_위반은_422_INVALID_ACCOUNT_INPUT이다(String tokenJson) {
    assertErrorCode(reset(tokenJson, quoted(NEW_PASSWORD)), 422, "INVALID_ACCOUNT_INPUT");
    verify(this.actionTokenStore, never()).lookup(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "123", "true", "{}", "[]"})
  void newPassword의_null과_비문자열은_422_INVALID_ACCOUNT_INPUT이다(String newPasswordJson) {
    long userId = registerUser(true);
    IssuedActionToken token = issueToken(userId);

    assertErrorCode(
        reset(quoted(token.compactToken()), newPasswordJson), 422, "INVALID_ACCOUNT_INPUT");
    verify(this.actionTokenStore, never()).lookup(any());
  }

  @Test
  void 완료_필드가_없으면_422_INVALID_ACCOUNT_INPUT이다() {
    long userId = registerUser(true);
    IssuedActionToken token = issueToken(userId);

    assertErrorCode(post(RESETS, "{}"), 422, "INVALID_ACCOUNT_INPUT");
    assertErrorCode(
        post(RESETS, "{\"newPassword\": %s}".formatted(quoted(NEW_PASSWORD))),
        422,
        "INVALID_ACCOUNT_INPUT");
    assertErrorCode(
        post(RESETS, "{\"token\": %s}".formatted(quoted(token.compactToken()))),
        422,
        "INVALID_ACCOUNT_INPUT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"\"", "\"   \""})
  void 빈_새_비밀번호와_공백_새_비밀번호는_422_INVALID_PASSWORD이고_토큰을_소비하지_않는다(String newPasswordJson) {
    long userId = registerUser(true);
    IssuedActionToken token = issueToken(userId);

    assertErrorCode(reset(quoted(token.compactToken()), newPasswordJson), 422, "INVALID_PASSWORD");
    verify(this.actionTokenStore, never()).consume(any());
    assertThat(passwordMatches(userId, OLD_PASSWORD)).isTrue();
  }

  @Test
  void 완료의_JSON_문법_오류는_400_MALFORMED_REQUEST_BODY다() {
    assertErrorCode(post(RESETS, "{\"token\": "), 400, "MALFORMED_REQUEST_BODY");
    assertErrorCode(post(RESETS, null), 400, "MALFORMED_REQUEST_BODY");
  }

  // ---------- 완료 API: 토큰·사용자 부적격 (400) ----------

  @Test
  void 앞뒤_공백이_붙은_토큰을_trim하지_않아_400이_된다() {
    long userId = registerUser(true);
    IssuedActionToken token = issueToken(userId);

    // trim했다면 204가 된다. opaque 값을 가공하지 않는다는 계약이 여기서 드러난다 (3.4.2절).
    assertErrorCode(
        reset(quoted(" " + token.compactToken() + " "), quoted(NEW_PASSWORD)),
        400,
        "ACTION_TOKEN_INVALID");
    assertThat(passwordMatches(userId, OLD_PASSWORD)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-jwt", "a.b.c", "eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0."})
  void 위조_토큰은_400_ACTION_TOKEN_INVALID다(String rawToken) {
    assertErrorCode(reset(quoted(rawToken), quoted(NEW_PASSWORD)), 400, "ACTION_TOKEN_INVALID");
  }

  @Test
  void 서명을_바꾼_토큰은_400이다() {
    long userId = registerUser(true);
    String compact = issueToken(userId).compactToken();
    String tampered = compact.substring(0, compact.lastIndexOf('.') + 1) + "AAAAAAAAAAAAAAAAAAAAAA";

    assertErrorCode(reset(quoted(tampered), quoted(NEW_PASSWORD)), 400, "ACTION_TOKEN_INVALID");
    assertThat(passwordMatches(userId, OLD_PASSWORD)).isTrue();
  }

  @Test
  void 만료된_토큰은_400이고_Redis를_조회하지_않는다() {
    long userId = registerUser(true);
    NimbusPasswordResetTokenCodec pastCodec =
        new NimbusPasswordResetTokenCodec(
            this.actionKeyRing,
            this.accessJwtProperties.issuer(),
            Clock.fixed(Instant.now().minus(Duration.ofMinutes(31)), ZoneOffset.UTC));
    IssuedActionToken expired = pastCodec.issue(new ActionTokenClaims(userId));
    this.eligibleTokenHashes.add(expired.tokenHash());

    assertErrorCode(
        reset(quoted(expired.compactToken()), quoted(NEW_PASSWORD)), 400, "ACTION_TOKEN_INVALID");
    verify(this.actionTokenStore, never()).lookup(any());
    assertThat(passwordMatches(userId, OLD_PASSWORD)).isTrue();
  }

  @Test
  void 이메일_인증_토큰은_400이다() {
    long userId = registerUser(true);
    IssuedActionToken emailToken =
        this.emailVerificationTokenIssuer.issue(new ActionTokenClaims(userId));
    this.eligibleTokenHashes.add(emailToken.tokenHash());

    // typ·aud·purpose가 모두 어긋난다.
    assertErrorCode(
        reset(quoted(emailToken.compactToken()), quoted(NEW_PASSWORD)),
        400,
        "ACTION_TOKEN_INVALID");
    assertThat(passwordMatches(userId, OLD_PASSWORD)).isTrue();
  }

  @Test
  void 존재하지_않는_사용자의_토큰은_400이다() {
    assertErrorCode(reset(issueToken(987_654_321L)), 400, "ACTION_TOKEN_INVALID");
    verify(this.actionTokenStore, never()).lookup(any());
  }

  @Test
  void 탈퇴한_사용자의_토큰은_400이다() {
    long userId = registerUser(true);
    withdraw(userId);

    assertErrorCode(reset(issueToken(userId)), 400, "ACTION_TOKEN_INVALID");
    verify(this.actionTokenStore, never()).lookup(any());
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
  void Redis_사전_조회_부적격은_400이고_유출_조회와_소비에_닿지_않는다(ActionTokenStore.LookupResult result) {
    long userId = registerUser(true);
    IssuedActionToken token = issueToken(userId);
    doReturn(result).when(this.actionTokenStore).lookup(any());

    assertErrorCode(reset(token), 400, "ACTION_TOKEN_INVALID");
    verify(this.compromisedPasswordChecker, never()).check(any());
    verify(this.actionTokenStore, never()).consume(any());
    assertThat(passwordMatches(userId, OLD_PASSWORD)).isTrue();
  }

  @Test
  void 사전_조회_뒤_다른_요청이_먼저_소비했으면_400이고_비밀번호가_그대로다() {
    long userId = registerUser(true);
    IssuedActionToken token = issueToken(userId);
    doReturn(ActionTokenStore.ConsumeResult.ALREADY_CONSUMED)
        .when(this.actionTokenStore)
        .consume(any());

    assertErrorCode(reset(token), 400, "ACTION_TOKEN_INVALID");
    assertThat(passwordMatches(userId, OLD_PASSWORD)).isTrue();
    verify(this.refreshTokenStore, never()).revokeAllFamilies(anyLong());
  }

  // ---------- 완료 API: 비밀번호 정책 ----------

  @Test
  void 유출된_비밀번호는_422_INVALID_PASSWORD이고_토큰을_소비하지_않는다() {
    long userId = registerUser(true);
    IssuedActionToken token = issueToken(userId);
    when(this.compromisedPasswordChecker.check(any()))
        .thenReturn(CompromisedPasswordChecker.CheckResult.COMPROMISED);

    assertErrorCode(reset(token), 422, "INVALID_PASSWORD");
    verify(this.actionTokenStore, never()).consume(any());
  }

  @Test
  void 유출_조회_장애는_503_PASSWORD_POLICY_UNAVAILABLE이고_토큰을_소비하지_않는다() {
    long userId = registerUser(true);
    IssuedActionToken token = issueToken(userId);
    when(this.compromisedPasswordChecker.check(any()))
        .thenReturn(CompromisedPasswordChecker.CheckResult.UNAVAILABLE);

    assertErrorCode(reset(token), 503, "PASSWORD_POLICY_UNAVAILABLE");
    verify(this.actionTokenStore, never()).consume(any());
    assertThat(passwordMatches(userId, OLD_PASSWORD)).isTrue();
  }

  // ---------- 완료 API: 저장소 장애 (503) ----------

  @Test
  void Redis_사전_조회_장애는_503이고_유출_조회에_닿지_않는다() {
    long userId = registerUser(true);
    IssuedActionToken token = issueToken(userId);
    doReturn(ActionTokenStore.LookupResult.UNAVAILABLE).when(this.actionTokenStore).lookup(any());

    assertErrorCode(reset(token), 503, "AUTH_STATE_UNAVAILABLE");
    verify(this.compromisedPasswordChecker, never()).check(any());
  }

  @Test
  void 소비_결과가_불확실하면_503이고_비밀번호가_그대로다() {
    long userId = registerUser(true);
    IssuedActionToken token = issueToken(userId);
    doReturn(ActionTokenStore.ConsumeResult.UNAVAILABLE).when(this.actionTokenStore).consume(any());

    assertErrorCode(reset(token), 503, "AUTH_STATE_UNAVAILABLE");
    assertThat(passwordMatches(userId, OLD_PASSWORD)).isTrue();
    assertThat(reload(userId).getAuthVersion()).isZero();
  }

  // ---------- 노출 ----------

  @Test
  void 완료_오류_응답에_토큰도_비밀번호도_내부_정보도_없다() {
    long userId = registerUser(true);
    IssuedActionToken token = issueToken(userId);
    doReturn(ActionTokenStore.LookupResult.CONSUMED).when(this.actionTokenStore).lookup(any());

    HttpResponse<String> response = reset(token);

    assertThat(response.body())
        .doesNotContain(token.compactToken())
        .doesNotContain(token.jti())
        .doesNotContain(token.tokenHash())
        .doesNotContain(NEW_PASSWORD)
        .doesNotContain(Long.toString(userId))
        .doesNotContain("redis")
        .doesNotContain("Exception");
  }

  @Test
  void 처리_로그에_이메일_토큰_비밀번호_해시가_없다() {
    List<CapturingAppender> appenders =
        List.of(
            CapturingAppender.attachTo(PasswordResetService.class),
            CapturingAppender.attachTo(GlobalExceptionHandler.class),
            CapturingAppender.attachTo(RequestTracingFilter.class));
    try {
      long userId = registerUser(true);
      IssuedActionToken token = issueToken(userId);
      request(quoted(this.email));
      when(this.refreshTokenStore.revokeAllFamilies(anyLong()))
          .thenReturn(RefreshTokenStore.AllFamiliesRevocationResult.UNAVAILABLE);
      assertEmptySuccess(reset(token), 204);
      doReturn(ActionTokenStore.LookupResult.UNAVAILABLE).when(this.actionTokenStore).lookup(any());
      reset(token);
      String storedHash = reload(userId).getPasswordHash();

      List<ILoggingEvent> logged = new ArrayList<>();
      appenders.forEach(appender -> logged.addAll(appender.events()));
      // 계열 정리 실패는 운영 경고로 남는다. 요청 로그만으로 이 단언이 통과하지 않도록 서비스 logger만 본다.
      assertThat(appenders.get(0).events())
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(event.getFormattedMessage()).contains("stage=unavailable");
              });
      for (ILoggingEvent event : logged) {
        assertThat(event.getFormattedMessage())
            .doesNotContain(this.email)
            .doesNotContain(token.compactToken())
            .doesNotContain(token.jti())
            .doesNotContain(token.tokenHash())
            .doesNotContain(NEW_PASSWORD)
            .doesNotContain(OLD_PASSWORD)
            .doesNotContain(storedHash);
      }
    } finally {
      appenders.forEach(CapturingAppender::detach);
    }
  }

  // ---------- 보안·HTTP 회귀 ----------

  @Test
  void 두_endpoint는_Authorization_없이_열려_있고_잘못된_헤더에도_영향받지_않는다() {
    assertEmptySuccess(
        request(quoted(this.email), HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"), 202);
    assertErrorCode(
        reset(quoted("not-a-jwt"), quoted(NEW_PASSWORD), HttpHeaders.AUTHORIZATION, "Bearer x"),
        400,
        "ACTION_TOKEN_INVALID");
    assertThat(this.client.get(HEALTH).statusCode()).isEqualTo(200);
  }

  @Test
  void 지원하지_않는_method는_405다() {
    assertErrorCode(this.client.get(REQUESTS), 405, "UNSUPPORTED_HTTP_METHOD");
    assertErrorCode(this.client.get(RESETS), 405, "UNSUPPORTED_HTTP_METHOD");
  }

  @Test
  void 보호_경로는_여전히_401이다() {
    assertErrorCode(this.client.get(ME), 401, "UNAUTHORIZED");
  }
}
