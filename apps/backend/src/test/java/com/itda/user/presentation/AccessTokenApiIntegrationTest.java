package com.itda.user.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.application.port.out.PasswordHasher;
import com.itda.user.application.port.out.RefreshFamilyIssueResult;
import com.itda.user.application.port.out.RefreshRotationResult;
import com.itda.user.application.port.out.RefreshTokenLookupResult;
import com.itda.user.application.port.out.RefreshTokenStore;
import com.itda.user.domain.Email;
import com.itda.user.domain.PasswordPolicy;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRepository;
import com.itda.user.domain.UserRole;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Access Token 갱신 API의 HTTP 계약을 실제 Tomcat으로 검증한다 (API 명세 3.3·3.3.1절).
 *
 * <p>JWT·Argon2id·MariaDB는 운영과 같은 bean을 쓰고 Redis 상태만 대역으로 바꾼다. Lua의 원자성은 가짜로 증명되지 않으므로 실제 Redis
 * 테스트가 따로 맡는다.
 *
 * <p>검증에 쓰는 Refresh Token은 실제 로그인 API로 발급받는다. 손으로 만든 문자열로는 서명·claim 검증 경로를 지나지 않는다.
 */
@WebApiTest
class AccessTokenApiIntegrationTest {

  private static final String AUTH_SESSIONS = "/api/v1/auth-sessions";
  private static final String ACCESS_TOKENS = "/api/v1/access-tokens";
  private static final String PLATFORM = "X-Client-Platform";
  private static final String ORIGIN = "Origin";
  private static final String COOKIE = "Cookie";

  /** 기존 CORS 허용 목록의 값이다. 이 이슈는 목록을 바꾸지 않는다. */
  private static final String ALLOWED_ORIGIN = "http://localhost:5173";

  private static final String DISALLOWED_ORIGIN = "https://not-allowed.example";

  private static final String PASSWORD = "충분히 긴 로그인 비밀번호";

  private static final AtomicLong SEQUENCE = new AtomicLong();

  private static final Pattern UUID_V4 =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  @LocalServerPort private int port;

  @Autowired private UserRepository userRepository;
  @Autowired private PasswordHasher passwordHasher;

  /** Redis 상태만 대역이다. 나머지 경로는 운영과 같은 bean을 쓴다. */
  @MockitoBean private RefreshTokenStore refreshTokenStore;

  /** 실제 검증 동작은 그대로 두고 호출 여부만 관찰한다. 전달 위치가 확정되지 않은 값이 검증기로 새는지를 보려면 필요하다. */
  @MockitoSpyBean
  private com.itda.user.application.port.out.RefreshTokenVerifier refreshTokenVerifier;

  private HttpTestClient client;
  private String username;
  private String refreshToken;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
    // 같은 H2를 공유하는 다른 통합 테스트와 username이 겹치지 않도록 클래스마다 다른 접두사를 쓴다.
    this.username = "refresher" + SEQUENCE.incrementAndGet();
    when(this.refreshTokenStore.issueFamily(any())).thenReturn(RefreshFamilyIssueResult.ISSUED);
    when(this.refreshTokenStore.lookup(any())).thenReturn(RefreshTokenLookupResult.ACTIVE);
    when(this.refreshTokenStore.rotate(any(), any())).thenReturn(RefreshRotationResult.ROTATED);
  }

  // ---------- 준비 ----------

  private void registerUser(boolean emailVerified) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    User user =
        User.create(
            this.username,
            this.passwordHasher.hash(PasswordPolicy.normalize(PASSWORD)).value(),
            UserRole.LEARNER,
            Email.of(this.username + "@example.test"),
            now);
    if (emailVerified) {
      user.verifyEmail(now);
    }
    this.userRepository.save(user);
  }

  /** 실제 로그인 API로 Refresh Token을 발급받는다. */
  private void loginForRefreshToken() {
    HttpResponse<String> response =
        this.client.send(
            "POST",
            AUTH_SESSIONS,
            """
            {"username": "%s", "password": "%s"}
            """
                .formatted(this.username, PASSWORD),
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            "MOBILE");
    assertThat(response.statusCode()).isEqualTo(201);
    this.refreshToken = (String) HttpTestClient.json(response).get("refreshToken");
  }

  private void registerAndLogin() {
    registerUser(true);
    loginForRefreshToken();
  }

  // ---------- 요청 도우미 ----------

  private HttpResponse<String> refreshWeb(String... extraHeaders) {
    String[] headers = new String[6 + extraHeaders.length];
    headers[0] = HttpHeaders.CONTENT_TYPE;
    headers[1] = "application/json";
    headers[2] = PLATFORM;
    headers[3] = "WEB";
    headers[4] = COOKIE;
    headers[5] = RefreshTokenCookie.NAME + "=" + this.refreshToken;
    System.arraycopy(extraHeaders, 0, headers, 6, extraHeaders.length);
    return this.client.send("POST", ACCESS_TOKENS, null, headers);
  }

  private HttpResponse<String> refreshMobile(String... extraHeaders) {
    return send("MOBILE", bodyWith(this.refreshToken), extraHeaders);
  }

  private HttpResponse<String> send(String platform, String body, String... extraHeaders) {
    String[] headers = new String[4 + extraHeaders.length];
    headers[0] = HttpHeaders.CONTENT_TYPE;
    headers[1] = "application/json";
    headers[2] = PLATFORM;
    headers[3] = platform;
    System.arraycopy(extraHeaders, 0, headers, 4, extraHeaders.length);
    return this.client.send("POST", ACCESS_TOKENS, body, headers);
  }

  private static String bodyWith(String token) {
    return token == null ? "{}" : "{\"refreshToken\": \"" + token + "\"}";
  }

  private static void assertErrorCode(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    Map<String, Object> json = HttpTestClient.json(response);
    assertThat(json).containsOnlyKeys("code", "message");
    assertThat(json).containsEntry("code", code);
    assertRequestId(response);
    // 갱신 실패는 브라우저의 기존 refresh 쿠키를 건드리지 않는다.
    assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  private static void assertRequestId(HttpResponse<String> response) {
    List<String> requestIds = response.headers().allValues("X-Request-Id");
    assertThat(requestIds).hasSize(1);
    assertThat(requestIds.get(0)).matches(UUID_V4);
    assertThat(UUID.fromString(requestIds.get(0)).version()).isEqualTo(4);
  }

  // ---------- WEB 성공 ----------

  @Test
  void WEB_갱신은_201과_공통_네_필드만_돌려준다() {
    registerAndLogin();

    HttpResponse<String> response = refreshWeb();

    assertThat(response.statusCode()).isEqualTo(201);
    Map<String, Object> json = HttpTestClient.json(response);
    assertThat(json).containsOnlyKeys("accessToken", "tokenType", "accessTokenExpiresAt", "user");
    assertThat(json).containsEntry("tokenType", "Bearer");
    assertRequestId(response);
  }

  @Test
  void WEB_갱신_body에_Refresh_필드가_없다() {
    registerAndLogin();

    String raw = refreshWeb().body();

    assertThat(raw).doesNotContain("refreshToken").doesNotContain("refreshTokenExpiresAt");
  }

  @Test
  void WEB_갱신은_새_Refresh를_쿠키로_돌려준다() {
    registerAndLogin();

    String cookie = refreshWeb().headers().firstValue(HttpHeaders.SET_COOKIE).orElseThrow();

    assertThat(cookie).startsWith(RefreshTokenCookie.NAME + "=");
    assertThat(cookie).contains("Max-Age=1209600");
    assertThat(cookie).contains("Path=/").contains("Secure").contains("HttpOnly");
    assertThat(cookie).contains("SameSite=Strict").doesNotContain("Domain=");
    // 회전이므로 제출한 값이 그대로 다시 나가면 안 된다.
    assertThat(cookie).doesNotContain(this.refreshToken);
  }

  // ---------- MOBILE 성공 ----------

  @Test
  void MOBILE_갱신은_Refresh_두_필드를_추가하고_쿠키를_붙이지_않는다() {
    registerAndLogin();

    HttpResponse<String> response = refreshMobile();

    assertThat(response.statusCode()).isEqualTo(201);
    Map<String, Object> json = HttpTestClient.json(response);
    assertThat(json)
        .containsOnlyKeys(
            "accessToken",
            "tokenType",
            "accessTokenExpiresAt",
            "refreshToken",
            "refreshTokenExpiresAt",
            "user");
    assertThat((String) json.get("refreshToken")).isNotBlank().isNotEqualTo(this.refreshToken);
    assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  // ---------- 플랫폼 판정 ----------

  @Test
  void 플랫폼_헤더가_없으면_400이다() {
    registerAndLogin();

    HttpResponse<String> response =
        this.client.send(
            "POST",
            ACCESS_TOKENS,
            bodyWith(this.refreshToken),
            HttpHeaders.CONTENT_TYPE,
            "application/json");

    assertErrorCode(response, 400, "INVALID_CLIENT_PLATFORM");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "web", "Web", "DESKTOP", "WEB,MOBILE"})
  void 지원하지_않는_플랫폼_값은_400이다(String platform) {
    registerAndLogin();

    assertErrorCode(send(platform, bodyWith(this.refreshToken)), 400, "INVALID_CLIENT_PLATFORM");
  }

  @Test
  void 플랫폼_헤더가_둘이면_400이다() {
    registerAndLogin();

    HttpResponse<String> response =
        this.client.send(
            "POST",
            ACCESS_TOKENS,
            bodyWith(this.refreshToken),
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            "WEB",
            PLATFORM,
            "MOBILE");

    assertErrorCode(response, 400, "INVALID_CLIENT_PLATFORM");
  }

  @ParameterizedTest
  @ValueSource(strings = {ALLOWED_ORIGIN, DISALLOWED_ORIGIN, "null"})
  void Origin이_있는_MOBILE_주장은_허용_여부와_무관하게_400이다(String origin) {
    registerAndLogin();

    // 플랫폼 판정이 CORS보다 앞에서 일어나야 비허용 Origin에서도 400이 나온다.
    assertErrorCode(refreshMobile(ORIGIN, origin), 400, "INVALID_CLIENT_PLATFORM");
  }

  // ---------- 전달 위치 ----------

  @Test
  void WEB이_body로_토큰을_보내면_400이다() {
    registerAndLogin();

    assertErrorCode(
        this.client.send(
            "POST",
            ACCESS_TOKENS,
            bodyWith(this.refreshToken),
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            "WEB",
            COOKIE,
            RefreshTokenCookie.NAME + "=" + this.refreshToken),
        400,
        "INVALID_CLIENT_PLATFORM");
  }

  @Test
  void MOBILE이_Refresh_쿠키를_보내면_400이다() {
    registerAndLogin();

    assertErrorCode(
        refreshMobile(COOKIE, RefreshTokenCookie.NAME + "=" + this.refreshToken),
        400,
        "INVALID_CLIENT_PLATFORM");
  }

  @Test
  void WEB에_쿠키가_없으면_401이다() {
    registerAndLogin();

    assertErrorCode(send("WEB", null), 401, "INVALID_REFRESH_TOKEN");
  }

  @Test
  void MOBILE에_토큰이_없으면_401이다() {
    registerAndLogin();

    assertErrorCode(send("MOBILE", "{}"), 401, "INVALID_REFRESH_TOKEN");
    assertErrorCode(send("MOBILE", "{\"refreshToken\": null}"), 401, "INVALID_REFRESH_TOKEN");
    assertErrorCode(send("MOBILE", "{\"refreshToken\": \"\"}"), 401, "INVALID_REFRESH_TOKEN");
  }

  @Test
  void WEB의_빈_refreshToken_필드는_금지_위치_사용이_아니다() {
    registerAndLogin();

    HttpResponse<String> response =
        this.client.send(
            "POST",
            ACCESS_TOKENS,
            "{\"refreshToken\": null}",
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            "WEB",
            COOKIE,
            RefreshTokenCookie.NAME + "=" + this.refreshToken);

    assertThat(response.statusCode()).isEqualTo(201);
  }

  @Test
  void 같은_이름의_Refresh_쿠키가_둘이면_401이다() {
    registerAndLogin();

    HttpResponse<String> response =
        this.client.send(
            "POST",
            ACCESS_TOKENS,
            null,
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            "WEB",
            COOKIE,
            RefreshTokenCookie.NAME
                + "="
                + this.refreshToken
                + "; "
                + RefreshTokenCookie.NAME
                + "="
                + this.refreshToken);

    // 단일 인증값을 고를 수 없다. 플랫폼과 전달 위치는 이미 확정됐으므로 플랫폼 오류가 아니다.
    assertErrorCode(response, 401, "INVALID_REFRESH_TOKEN");
  }

  @Test
  void 빈_쿠키와_유효_쿠키가_함께_오면_401이고_유효_쿠키를_쓰지_않는다() {
    registerAndLogin();
    org.mockito.Mockito.clearInvocations(this.refreshTokenStore, this.refreshTokenVerifier);

    HttpResponse<String> response =
        this.client.send(
            "POST",
            ACCESS_TOKENS,
            null,
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            "WEB",
            COOKIE,
            RefreshTokenCookie.NAME + "=; " + RefreshTokenCookie.NAME + "=" + this.refreshToken);

    // 빈 값을 먼저 걸러 내면 유효한 쪽 하나만 남아 정상 요청으로 둔갑한다.
    assertErrorCode(response, 401, "INVALID_REFRESH_TOKEN");
    // 어떤 값도 JWT 검증기나 저장소로 넘어가지 않았다.
    org.mockito.Mockito.verifyNoInteractions(this.refreshTokenVerifier);
    org.mockito.Mockito.verifyNoInteractions(this.refreshTokenStore);
  }

  @Test
  void 빈_쿠키만_오면_401이다() {
    registerAndLogin();
    org.mockito.Mockito.clearInvocations(this.refreshTokenStore, this.refreshTokenVerifier);

    HttpResponse<String> response =
        this.client.send(
            "POST",
            ACCESS_TOKENS,
            null,
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            "WEB",
            COOKIE,
            RefreshTokenCookie.NAME + "=");

    assertErrorCode(response, 401, "INVALID_REFRESH_TOKEN");
    org.mockito.Mockito.verifyNoInteractions(this.refreshTokenVerifier);
  }

  // ---------- 입력 형식 ----------

  @Test
  void 잘못된_JSON은_400_MALFORMED_REQUEST_BODY다() {
    registerAndLogin();

    assertErrorCode(send("MOBILE", "{\"refreshToken\": "), 400, "MALFORMED_REQUEST_BODY");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"{\"refreshToken\": 1}", "{\"refreshToken\": true}", "{\"refreshToken\": {}}"})
  void 필드_타입이_다르면_400이다(String rawBody) {
    registerAndLogin();

    assertErrorCode(send("MOBILE", rawBody), 400, "MALFORMED_REQUEST_BODY");
  }

  // ---------- 토큰·저장소 판정 ----------

  @Test
  void 위조된_토큰은_401이다() {
    registerAndLogin();

    assertErrorCode(send("MOBILE", bodyWith("not-a-real-token")), 401, "INVALID_REFRESH_TOKEN");
  }

  @Test
  void Access_Token을_Refresh_자리에_넣으면_401이다() {
    registerAndLogin();
    String accessToken = (String) HttpTestClient.json(refreshMobile()).get("accessToken");

    assertErrorCode(send("MOBILE", bodyWith(accessToken)), 401, "INVALID_REFRESH_TOKEN");
  }

  @ParameterizedTest
  @ValueSource(strings = {"NOT_FOUND", "HASH_MISMATCH", "FAMILY_REVOKED"})
  void 결정적으로_무효인_Redis_상태는_401이다(String lookup) {
    registerAndLogin();
    doReturn(RefreshTokenLookupResult.valueOf(lookup)).when(this.refreshTokenStore).lookup(any());

    assertErrorCode(refreshMobile(), 401, "INVALID_REFRESH_TOKEN");
  }

  @Test
  void Redis_조회_장애는_503이고_토큰을_돌려주지_않는다() {
    registerAndLogin();
    doReturn(RefreshTokenLookupResult.UNAVAILABLE).when(this.refreshTokenStore).lookup(any());

    HttpResponse<String> response = refreshMobile();

    assertErrorCode(response, 503, "AUTH_STATE_UNAVAILABLE");
    assertThat(response.body()).doesNotContain("accessToken");
  }

  @Test
  void 회전_결과가_재사용이면_401이다() {
    registerAndLogin();
    doReturn(RefreshRotationResult.REUSED).when(this.refreshTokenStore).rotate(any(), any());

    assertErrorCode(refreshMobile(), 401, "INVALID_REFRESH_TOKEN");
  }

  @Test
  void 회전_장애는_503이다() {
    registerAndLogin();
    doReturn(RefreshRotationResult.UNAVAILABLE).when(this.refreshTokenStore).rotate(any(), any());

    assertErrorCode(refreshMobile(), 503, "AUTH_STATE_UNAVAILABLE");
  }

  @Test
  void 이메일_미인증_계정은_403이다() {
    registerUser(true);
    loginForRefreshToken();
    User user = this.userRepository.findByUsername(this.username).orElseThrow();
    unverifyEmail(user);

    assertErrorCode(refreshMobile(), 403, "EMAIL_VERIFICATION_REQUIRED");
  }

  @Test
  void 탈퇴한_계정은_401이다() {
    registerAndLogin();
    User user = this.userRepository.findByUsername(this.username).orElseThrow();
    user.withdraw(
        "withdrawn-" + this.username,
        Email.of("withdrawn-" + this.username + "@example.invalid"),
        "discarded$hash",
        LocalDateTime.now(ZoneOffset.UTC));
    this.userRepository.save(user);

    assertErrorCode(refreshMobile(), 401, "INVALID_REFRESH_TOKEN");
  }

  /** 이메일 인증만 되돌린다. 도메인에는 이 전이가 없으므로 테스트에서만 리플렉션으로 만든다. */
  private void unverifyEmail(User user) {
    try {
      java.lang.reflect.Field field = User.class.getDeclaredField("emailVerifiedAt");
      field.setAccessible(true);
      field.set(user, null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("테스트 fixture를 만들지 못했습니다.", e);
    }
    this.userRepository.save(user);
  }

  // ---------- CSRF·CORS 회귀 ----------

  @Test
  void Origin이_없는_WEB_갱신은_허용된다() {
    registerAndLogin();

    // 브라우저가 아닌 클라이언트는 Origin을 붙이지 않는다. 부재를 거부하면 로그인과 규칙이 갈린다.
    assertThat(refreshWeb().statusCode()).isEqualTo(201);
  }

  @Test
  void 허용_Origin의_WEB_갱신은_정상_처리된다() {
    registerAndLogin();

    assertThat(refreshWeb(ORIGIN, ALLOWED_ORIGIN).statusCode()).isEqualTo(201);
  }

  @Test
  void 허용되지_않은_Origin의_WEB_요청은_기존_CORS_정책대로_거부된다() {
    registerAndLogin();

    HttpResponse<String> response = refreshWeb(ORIGIN, DISALLOWED_ORIGIN);

    // 업무 처리까지 도달하지 않는다. 업무 오류 code가 아니라 CORS 거부다.
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).doesNotContain("accessToken").doesNotContain("INVALID_");
  }

  @Test
  void 허용되지_않은_Origin의_preflight는_거부된다() {
    HttpResponse<String> preflight =
        this.client.send(
            "OPTIONS",
            ACCESS_TOKENS,
            null,
            ORIGIN,
            DISALLOWED_ORIGIN,
            "Access-Control-Request-Method",
            "POST",
            "Access-Control-Request-Headers",
            PLATFORM);

    assertThat(preflight.statusCode()).isEqualTo(403);
    assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
  }

  @Test
  void 허용_Origin의_preflight는_통과하고_커스텀_헤더를_허용한다() {
    HttpResponse<String> preflight =
        this.client.send(
            "OPTIONS",
            ACCESS_TOKENS,
            null,
            ORIGIN,
            ALLOWED_ORIGIN,
            "Access-Control-Request-Method",
            "POST",
            "Access-Control-Request-Headers",
            PLATFORM);

    assertThat(preflight.statusCode()).isEqualTo(200);
    assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin"))
        .contains(ALLOWED_ORIGIN);
  }

  @Test
  void 커스텀_헤더가_없는_cross_site_form_요청은_플랫폼_판정에서_거부된다() {
    registerAndLogin();

    // 브라우저의 cross-site form POST는 커스텀 헤더를 실을 수 없다. 그 모양을 그대로 보낸다.
    HttpResponse<String> response =
        this.client.send(
            "POST",
            ACCESS_TOKENS,
            "refreshToken=" + this.refreshToken,
            HttpHeaders.CONTENT_TYPE,
            "application/x-www-form-urlencoded",
            ORIGIN,
            ALLOWED_ORIGIN,
            COOKIE,
            RefreshTokenCookie.NAME + "=" + this.refreshToken);

    assertErrorCode(response, 400, "INVALID_CLIENT_PLATFORM");
  }

  @Test
  void CORS_허용_Origin_목록은_이_이슈에서_바뀌지_않았다() {
    // 업무 계층에 목록을 복제하지 않았으므로, 실제 동작으로만 확인한다.
    assertThat(allowsOrigin(ALLOWED_ORIGIN)).isTrue();
    assertThat(allowsOrigin("http://localhost:8081")).isTrue();
    assertThat(allowsOrigin(DISALLOWED_ORIGIN)).isFalse();
    assertThat(allowsOrigin("http://localhost:5174")).isFalse();
  }

  private boolean allowsOrigin(String origin) {
    HttpResponse<String> preflight =
        this.client.send(
            "OPTIONS",
            ACCESS_TOKENS,
            null,
            ORIGIN,
            origin,
            "Access-Control-Request-Method",
            "POST",
            "Access-Control-Request-Headers",
            PLATFORM);
    return preflight.statusCode() == 200;
  }

  // ---------- HTTP 회귀 ----------

  @Test
  void 인증_없이_접근할_수_있다() {
    registerAndLogin();

    // 갱신은 Access Bearer를 인증 수단으로 쓰지 않는다.
    assertThat(refreshMobile().statusCode()).isEqualTo(201);
  }

  @Test
  void 지원하지_않는_method는_405다() {
    assertErrorCode(this.client.get(ACCESS_TOKENS), 405, "UNSUPPORTED_HTTP_METHOD");
  }

  @Test
  void 오류_응답에_토큰도_해시도_저장소_정보도_없다() {
    registerAndLogin();
    doReturn(RefreshTokenLookupResult.NOT_FOUND).when(this.refreshTokenStore).lookup(any());

    String raw = refreshMobile().body();

    assertThat(raw)
        .doesNotContain(this.refreshToken)
        .doesNotContain(this.username)
        .doesNotContain("redis")
        .doesNotContain("jti")
        .doesNotContain("familyId")
        .doesNotContain("Exception");
  }
}
