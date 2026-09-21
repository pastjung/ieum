package com.itda.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserRepository;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 로그인 API의 HTTP 계약을 실제 Tomcat으로 검증한다 (API 명세 3.3·3.3.1절).
 *
 * <p>Argon2id와 JWT는 운영과 같은 bean을 쓴다. Redis 상태만 대역으로 바꿔 등록 결과별 분기를 만든다 — Lua 원자성은 가짜로 증명되지 않으므로 실제
 * Redis 테스트가 따로 맡는다.
 */
@WebApiTest
class AuthSessionApiIntegrationTest {

  private static final String AUTH_SESSIONS = "/api/v1/auth-sessions";
  private static final String PLATFORM = "X-Client-Platform";
  private static final String ORIGIN = "Origin";

  /** 기존 CORS 허용 목록의 값이다. 허용되지 않은 Origin은 CORS가 먼저 거부해 플랫폼 판정까지 오지 않는다. */
  private static final String ALLOWED_ORIGIN = "http://localhost:5173";

  private static final String PASSWORD = "충분히 긴 로그인 비밀번호";

  private static final AtomicLong SEQUENCE = new AtomicLong();

  private static final Pattern UUID_V4 =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  /** {@code Expires}가 RFC 1123 GMT인지 확인할 때 쓴다. */
  private static final DateTimeFormatter RFC_1123 =
      DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);

  @LocalServerPort private int port;
  @Autowired private org.springframework.context.ApplicationContext applicationContext;

  /** 플랫폼이 확정되지 않은 요청이 DB를 건드리지 않는지 보려면 실제 bean의 호출을 관찰해야 한다. */
  @MockitoSpyBean private UserRepository userRepository;

  /** Redis 상태만 대역이다. 나머지 경로(Argon2id·JWT·조건부 UPDATE)는 운영과 같은 bean을 쓴다. */
  @MockitoBean private RefreshTokenStore refreshTokenStore;

  private HttpTestClient client;
  private String username;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
    this.username = "learner" + SEQUENCE.incrementAndGet();
    when(this.refreshTokenStore.issueFamily(any()))
        .thenReturn(RefreshTokenStore.FamilyIssueResult.ISSUED);
  }

  // ---------- 요청 도우미 ----------

  private static String body(String username, String password) {
    return """
        {"username": %s, "password": %s}
        """
        .formatted(quote(username), quote(password));
  }

  private static String quote(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private HttpResponse<String> login(String platform, String... extraHeaders) {
    return loginWith(body(this.username, PASSWORD), platform, extraHeaders);
  }

  private HttpResponse<String> loginWith(String rawBody, String platform, String... extraHeaders) {
    String[] headers = new String[4 + extraHeaders.length];
    headers[0] = HttpHeaders.CONTENT_TYPE;
    headers[1] = "application/json";
    headers[2] = PLATFORM;
    headers[3] = platform;
    System.arraycopy(extraHeaders, 0, headers, 4, extraHeaders.length);
    return this.client.send("POST", AUTH_SESSIONS, rawBody, headers);
  }

  private long registerUser(boolean emailVerified) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    User user =
        User.create(
            this.username,
            argon2idHashOf(),
            UserRole.LEARNER,
            Email.of(this.username + "@example.test"),
            now);
    if (emailVerified) {
      user.verifyEmail(now);
    }
    return this.userRepository.save(user).getId();
  }

  /** 운영과 같은 해시기로 만든 값이어야 실제 Argon2id 검증 경로를 지난다. */
  @Autowired private com.itda.user.service.PasswordHasher passwordHasher;

  private String argon2idHashOf() {
    return this.passwordHasher
        .hash(com.itda.user.domain.PasswordPolicy.normalize(PASSWORD))
        .value();
  }

  private static void assertErrorCode(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    Map<String, Object> json = HttpTestClient.json(response);
    assertThat(json).containsOnlyKeys("code", "message");
    assertThat(json).containsEntry("code", code);
    assertRequestId(response);
    // 실패 응답은 브라우저의 기존 refresh 쿠키를 건드리지 않는다.
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
  void WEB_로그인은_201과_공통_네_필드만_돌려준다() {
    registerUser(true);

    HttpResponse<String> response = login("WEB");

    assertThat(response.statusCode()).isEqualTo(201);
    Map<String, Object> json = HttpTestClient.json(response);
    assertThat(json).containsOnlyKeys("accessToken", "tokenType", "accessTokenExpiresAt", "user");
    assertThat(json).containsEntry("tokenType", "Bearer");
    assertThat((String) json.get("accessToken")).isNotBlank();
    assertThat((String) json.get("accessTokenExpiresAt")).endsWith("Z").contains("T");
    assertRequestId(response);
  }

  @Test
  void WEB_body에_Refresh_필드가_없다() {
    registerUser(true);

    String raw = login("WEB").body();

    // 계약이 "어떤 경우에도 포함하지 않는다"고 정했다. null로도 두지 않는다.
    assertThat(raw).doesNotContain("refreshToken").doesNotContain("refreshTokenExpiresAt");
  }

  @Test
  void WEB_응답의_사용자_요약이_내_정보와_같은_구조다() {
    long userId = registerUser(true);

    @SuppressWarnings("unchecked")
    Map<String, Object> user = (Map<String, Object>) HttpTestClient.json(login("WEB")).get("user");

    assertThat(user)
        .containsOnlyKeys(
            "userId", "username", "email", "role", "emailVerified", "level", "createdAt");
    assertThat(user).containsEntry("userId", Long.toString(userId));
    assertThat(user).containsEntry("username", this.username);
    assertThat(user).containsEntry("role", "LEARNER");
    assertThat(user).containsEntry("emailVerified", true);
    assertThat(user).containsEntry("level", null);
  }

  @Test
  void WEB_Refresh_쿠키의_속성이_계약과_같다() {
    registerUser(true);

    String cookie = login("WEB").headers().firstValue(HttpHeaders.SET_COOKIE).orElseThrow();

    assertThat(cookie).startsWith("__Host-refreshToken=");
    assertThat(cookie).contains("Max-Age=1209600");
    assertThat(cookie).contains("Path=/");
    assertThat(cookie).contains("Secure");
    assertThat(cookie).contains("HttpOnly");
    assertThat(cookie).contains("SameSite=Strict");
    // __Host- 접두사는 Domain을 금지한다.
    assertThat(cookie).doesNotContain("Domain=");
  }

  @Test
  void WEB_쿠키의_Expires가_그_요청으로_발급한_Refresh의_만료_시각과_같다() {
    registerUser(true);
    ArgumentCaptor<RefreshTokenStore.TokenRecord> registered =
        ArgumentCaptor.forClass(RefreshTokenStore.TokenRecord.class);

    HttpResponse<String> response = login("WEB");

    // 비교 기준은 "같은 요청에서 Redis에 등록된 Refresh의 만료 시각" 하나뿐이다.
    // 다른 요청의 응답을 기준으로 삼고 허용 오차를 두면, Expires를 "직렬화 시각 + Max-Age"로 계산하는
    // 구현(Spring ResponseCookie의 기본 동작)도 그 오차 안에 들어와 그대로 통과한다.
    org.mockito.Mockito.verify(this.refreshTokenStore).issueFamily(registered.capture());
    String cookie = response.headers().firstValue(HttpHeaders.SET_COOKIE).orElseThrow();

    Instant fromCookie = Instant.from(RFC_1123.parse(valueOf(cookie, "Expires")));
    assertThat(fromCookie.getEpochSecond()).isEqualTo(registered.getValue().expiresAtEpochSecond());
  }

  private static String valueOf(String cookie, String attribute) {
    for (String part : cookie.split(";")) {
      String trimmed = part.trim();
      if (trimmed.startsWith(attribute + "=")) {
        return trimmed.substring(attribute.length() + 1);
      }
    }
    throw new IllegalArgumentException(attribute + " 속성이 없습니다.");
  }

  // ---------- MOBILE 성공 ----------

  @Test
  void MOBILE_로그인은_Refresh_두_필드를_추가한다() {
    registerUser(true);

    HttpResponse<String> response = login("MOBILE");

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
    assertThat((String) json.get("refreshToken")).isNotBlank();
    assertThat((String) json.get("refreshTokenExpiresAt")).endsWith("Z").contains("T");
  }

  @Test
  void MOBILE_응답에는_쿠키가_없다() {
    registerUser(true);

    // 네이티브 앱에는 쿠키 저장소가 없고, 있어도 OS 보안 저장소가 아니다.
    assertThat(login("MOBILE").headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  // ---------- 플랫폼 판정 (400) ----------

  @Test
  void 플랫폼_헤더가_없으면_400이다() {
    registerUser(true);

    HttpResponse<String> response =
        this.client.send(
            "POST",
            AUTH_SESSIONS,
            body(this.username, PASSWORD),
            HttpHeaders.CONTENT_TYPE,
            "application/json");

    assertErrorCode(response, 400, "INVALID_CLIENT_PLATFORM");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "web", "Web", "MOBILE_APP", "DESKTOP", "WEB,MOBILE"})
  void 지원하지_않는_플랫폼_값은_400이다(String platform) {
    registerUser(true);

    // 대소문자 변환으로 느슨하게 받아 주지 않는다. 앞뒤 공백은 HTTP 계층이 이미 제거하므로
    // "우리가 trim하지 않는다"는 성질은 ClientPlatformTest가 단위로 고정한다.
    assertErrorCode(login(platform), 400, "INVALID_CLIENT_PLATFORM");
  }

  @Test
  void 플랫폼_헤더가_둘이면_400이다() {
    registerUser(true);

    HttpResponse<String> response =
        this.client.send(
            "POST",
            AUTH_SESSIONS,
            body(this.username, PASSWORD),
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            "WEB",
            PLATFORM,
            "MOBILE");

    assertErrorCode(response, 400, "INVALID_CLIENT_PLATFORM");
  }

  @ParameterizedTest
  @ValueSource(strings = {ALLOWED_ORIGIN, "https://not-allowed.example", "null"})
  void Origin이_있는_MOBILE_주장은_허용_여부와_무관하게_400이다(String origin) {
    registerUser(true);

    // 허용 목록에 없는 Origin은 CORS가 먼저 403으로 끝낼 수 있다. 계약은 "허용 목록 여부와 무관하게 400"이므로
    // 플랫폼 판정이 CORS보다 앞에서 일어나야 한다 (ClientPlatformFilter).
    assertErrorCode(login("MOBILE", ORIGIN, origin), 400, "INVALID_CLIENT_PLATFORM");
  }

  @ParameterizedTest
  @ValueSource(strings = {"MOBILE", "DESKTOP"})
  void 플랫폼이_확정되지_않은_요청은_저장소를_건드리지_않는다(String platform) {
    registerUser(true);
    org.mockito.Mockito.clearInvocations(this.userRepository);

    login(platform, ORIGIN, "https://not-allowed.example");

    // 전달 방식이 확정되지 않은 요청으로 사용자 조회·Argon2id·JWT 발급·Redis 등록을 시작하지 않는다.
    // 사용자 조회가 없으면 그 뒤 단계도 시작될 수 없다.
    org.mockito.Mockito.verifyNoInteractions(this.userRepository);
    org.mockito.Mockito.verifyNoInteractions(this.refreshTokenStore);
  }

  @Test
  void Origin이_있는_WEB_요청은_플랫폼_때문에_거부되지_않는다() {
    registerUser(true);

    // 허용 Origin 검사는 CORS의 책임이며 로그인 업무 코드가 다시 판정하지 않는다.
    assertThat(login("WEB", ORIGIN, ALLOWED_ORIGIN).statusCode()).isEqualTo(201);
  }

  @Test
  void 허용되지_않은_Origin의_WEB_요청은_기존_CORS_정책대로_거부된다() {
    registerUser(true);

    // 이 이슈가 CORS를 느슨하게 만들지 않았다는 회귀 확인이다.
    assertThat(login("WEB", ORIGIN, "https://not-allowed.example").statusCode()).isEqualTo(403);
  }

  @Test
  void Origin이_없는_MOBILE_요청은_정상_처리된다() {
    registerUser(true);

    assertThat(login("MOBILE").statusCode()).isEqualTo(201);
  }

  @Test
  void 플랫폼이_잘못되면_자격_증명을_확인하기_전에_끊는다() {
    // 계정이 없어도 같은 400이다. 플랫폼이 확정되지 않은 요청으로 DB·Argon2id를 건드리지 않는다.
    assertErrorCode(login("DESKTOP"), 400, "INVALID_CLIENT_PLATFORM");
  }

  @Test
  void 플랫폼_오류도_Accept가_JSON을_거부하면_상태만_남기고_body를_생략한다() {
    // 이 오류는 DispatcherServlet 밖의 filter가 직접 쓴다. 그래도 협상 규칙은 공통 규칙 그대로여야 한다
    // (api.md 「오류 상태 보존과 body 생략」, API 명세 2.2절).
    HttpResponse<String> response =
        this.client.send(
            "POST",
            AUTH_SESSIONS,
            body(this.username, PASSWORD),
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            "DESKTOP",
            HttpHeaders.ACCEPT,
            "text/html");

    // 원래 상태를 유지한다. JSON을 보낼 수 없다는 이유로 406으로 바꾸지 않고, CORS 403으로 바뀌지도 않는다.
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).isEmpty();
    // body가 없으므로 JSON Content-Type을 붙이지 않는다.
    assertThat(response.headers().allValues(HttpHeaders.CONTENT_TYPE))
        .noneMatch(value -> value.contains("application/json"));
    assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
    // body가 없는 응답에도 X-Request-Id는 정확히 하나 포함한다.
    assertRequestId(response);
  }

  // ---------- 입력 형식 ----------

  @Test
  void 잘못된_JSON은_400_MALFORMED_REQUEST_BODY다() {
    assertErrorCode(loginWith("{\"username\": ", "WEB"), 400, "MALFORMED_REQUEST_BODY");
    assertErrorCode(loginWith((String) null, "WEB"), 400, "MALFORMED_REQUEST_BODY");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"username\": 1, \"password\": \"x\"}",
        "{\"username\": \"learner01\", \"password\": true}",
        "{\"username\": {}, \"password\": \"x\"}",
        "{\"username\": [], \"password\": \"x\"}"
      })
  void 필드_타입이_다르면_400이다(String rawBody) {
    // Jackson이 숫자·불리언을 문자열로 바꿔 받으면 타입 불일치가 422로 둔갑한다.
    assertErrorCode(loginWith(rawBody, "WEB"), 400, "MALFORMED_REQUEST_BODY");
  }

  @Test
  void 필수_필드가_없거나_null이면_422다() {
    assertErrorCode(loginWith("{}", "WEB"), 422, "INVALID_ACCOUNT_INPUT");
    assertErrorCode(loginWith(body(null, PASSWORD), "WEB"), 422, "INVALID_ACCOUNT_INPUT");
    assertErrorCode(loginWith(body(this.username, null), "WEB"), 422, "INVALID_ACCOUNT_INPUT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "ab", "-abc", "abc-", "learner@01"})
  void username_형식_위반은_422다(String username) {
    assertErrorCode(loginWith(body(username, PASSWORD), "WEB"), 422, "INVALID_ACCOUNT_INPUT");
  }

  @Test
  void 빈_비밀번호는_422이고_공백만_있는_비밀번호는_401이다() {
    registerUser(true);

    assertErrorCode(loginWith(body(this.username, ""), "WEB"), 422, "INVALID_ACCOUNT_INPUT");
    // 비밀번호는 trim하지 않는다. 공백도 값이므로 형식 위반이 아니라 자격 증명 실패다.
    assertErrorCode(loginWith(body(this.username, "   "), "WEB"), 401, "INVALID_CREDENTIALS");
  }

  @Test
  void username은_trim해서_조회한다() {
    registerUser(true);

    assertThat(loginWith(body("  " + this.username + "  ", PASSWORD), "WEB").statusCode())
        .isEqualTo(201);
  }

  // ---------- 자격 증명 (401·403) ----------

  @Test
  void 계정이_없으면_401이다() {
    assertErrorCode(login("WEB"), 401, "INVALID_CREDENTIALS");
  }

  @Test
  void 비밀번호가_틀리면_401이다() {
    registerUser(true);

    assertErrorCode(
        loginWith(body(this.username, PASSWORD + "x"), "WEB"), 401, "INVALID_CREDENTIALS");
  }

  @Test
  void 탈퇴_계정은_401이다() {
    long userId = registerUser(true);
    User user = this.userRepository.findById(userId).orElseThrow();
    user.withdraw(
        "withdrawn-" + userId,
        Email.of("withdrawn-" + userId + "@example.invalid"),
        "discarded$hash",
        LocalDateTime.now(ZoneOffset.UTC));
    this.userRepository.save(user);

    assertErrorCode(login("WEB"), 401, "INVALID_CREDENTIALS");
  }

  @Test
  void 이메일_미인증_계정은_403이다() {
    registerUser(false);

    assertErrorCode(login("WEB"), 403, "EMAIL_VERIFICATION_REQUIRED");
  }

  @Test
  void 이메일_미인증이어도_비밀번호가_틀리면_401이다() {
    registerUser(false);

    // 계정 상태를 알려 주기 전에 자격 증명부터 통과해야 한다.
    assertErrorCode(
        loginWith(body(this.username, PASSWORD + "x"), "WEB"), 401, "INVALID_CREDENTIALS");
  }

  @Test
  void 계정_없음과_비밀번호_오류의_응답이_같다() {
    registerUser(true);
    HttpResponse<String> wrongPassword = loginWith(body(this.username, PASSWORD + "x"), "WEB");

    this.username = "absent" + SEQUENCE.incrementAndGet();
    HttpResponse<String> noAccount = login("WEB");

    assertThat(noAccount.statusCode()).isEqualTo(wrongPassword.statusCode());
    assertThat(HttpTestClient.json(noAccount)).isEqualTo(HttpTestClient.json(wrongPassword));
  }

  // ---------- Redis 등록 (503) ----------

  @Test
  void Redis_결과_불확실이면_503이고_토큰을_돌려주지_않는다() {
    registerUser(true);
    doReturn(RefreshTokenStore.FamilyIssueResult.UNAVAILABLE)
        .when(this.refreshTokenStore)
        .issueFamily(any());

    HttpResponse<String> response = login("WEB");

    assertErrorCode(response, 503, "AUTH_STATE_UNAVAILABLE");
    assertThat(response.body()).doesNotContain("accessToken").doesNotContain("refreshToken");
  }

  // ---------- 노출 ----------

  @Test
  void 오류_응답에_아이디도_비밀번호도_저장소_정보도_없다() {
    registerUser(true);

    String raw = loginWith(body(this.username, PASSWORD + "x"), "WEB").body();

    assertThat(raw)
        .doesNotContain(this.username)
        .doesNotContain(PASSWORD)
        .doesNotContain("argon2")
        .doesNotContain("redis")
        .doesNotContain("select")
        .doesNotContain("Exception");
  }

  // ---------- HTTP 회귀 ----------

  @Test
  void 인증_없이_접근할_수_있다() {
    registerUser(true);

    // 로그인은 공개 API다. Authorization이 없다는 이유로 401이 되면 안 된다.
    assertThat(login("WEB").statusCode()).isEqualTo(201);
  }

  @Test
  void 지원하지_않는_method는_405다() {
    assertErrorCode(this.client.get(AUTH_SESSIONS), 405, "UNSUPPORTED_HTTP_METHOD");
  }

  @Test
  void 보호_경로는_여전히_401이다() {
    assertErrorCode(this.client.get("/api/v1/users/me"), 401, "UNAUTHORIZED");
  }

  @Test
  void 플랫폼_filter가_Security_chain보다_앞_순서로_등록된다() {
    // 뒤에 있으면 CORS가 먼저 403으로 끝내 계약이 정한 400이 나가지 않는다. 이 순서가 F-1 수정의 핵심이다.
    org.springframework.boot.web.servlet.FilterRegistrationBean<?> registration =
        this.applicationContext.getBean(
            "clientPlatformFilterRegistration",
            org.springframework.boot.web.servlet.FilterRegistrationBean.class);

    assertThat(registration.getOrder())
        .isLessThan(
            org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties
                .DEFAULT_FILTER_ORDER);
    // 플랫폼 헤더가 필수인 세 경로에만 걸린다. 다른 공개 API가 이 헤더를 요구하게 되면 안 된다.
    assertThat(registration.getUrlPatterns())
        .containsExactlyInAnyOrderElementsOf(ClientPlatformFilter.PATHS)
        .contains(ClientPlatformFilter.LOGIN_PATH);
  }
}
