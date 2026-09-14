package com.itda.user.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.application.port.out.PasswordHasher;
import com.itda.user.application.port.out.RefreshFamilyIssueResult;
import com.itda.user.application.port.out.RefreshFamilyRevocationResult;
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
 * 로그아웃 API의 HTTP 계약을 실제 Tomcat으로 검증한다 (API 명세 3.3·3.3.1·3.6.2절).
 *
 * <p>이 테스트의 핵심은 <b>삭제 쿠키가 판정 결과와 무관하게 나가는지</b>다. {@code 401}·{@code 503}은 공통 오류 처리가 응답을 만들기 때문에,
 * 컨트롤러가 붙인 헤더가 그 경로에서도 살아남는지는 실제 컨테이너로만 확인할 수 있다.
 */
@WebApiTest
class AuthSessionRevocationApiIntegrationTest {

  private static final String AUTH_SESSIONS = "/api/v1/auth-sessions";
  private static final String REVOCATIONS = "/api/v1/auth-session-revocations";
  private static final String PLATFORM = "X-Client-Platform";
  private static final String ORIGIN = "Origin";
  private static final String COOKIE = "Cookie";

  private static final String ALLOWED_ORIGIN = "http://localhost:5173";
  private static final String DISALLOWED_ORIGIN = "https://not-allowed.example";
  private static final String PASSWORD = "충분히 긴 로그인 비밀번호";

  private static final AtomicLong SEQUENCE = new AtomicLong();

  private static final Pattern UUID_V4 =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  @LocalServerPort private int port;

  @Autowired private UserRepository userRepository;
  @Autowired private PasswordHasher passwordHasher;

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
    this.username = "revoker" + SEQUENCE.incrementAndGet();
    when(this.refreshTokenStore.issueFamily(any())).thenReturn(RefreshFamilyIssueResult.ISSUED);
    when(this.refreshTokenStore.lookup(any())).thenReturn(RefreshTokenLookupResult.ACTIVE);
    when(this.refreshTokenStore.revokeFamily(anyLong(), anyString()))
        .thenReturn(RefreshFamilyRevocationResult.REVOKED);
    registerAndLogin();
  }

  private void registerAndLogin() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    User user =
        User.create(
            this.username,
            this.passwordHasher.hash(PasswordPolicy.normalize(PASSWORD)).value(),
            UserRole.LEARNER,
            Email.of(this.username + "@example.test"),
            now);
    user.verifyEmail(now);
    this.userRepository.save(user);

    HttpResponse<String> login =
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
    assertThat(login.statusCode()).isEqualTo(201);
    this.refreshToken = (String) HttpTestClient.json(login).get("refreshToken");
  }

  // ---------- 요청 도우미 ----------

  private HttpResponse<String> revokeWeb(String... extraHeaders) {
    String[] headers = new String[6 + extraHeaders.length];
    headers[0] = HttpHeaders.CONTENT_TYPE;
    headers[1] = "application/json";
    headers[2] = PLATFORM;
    headers[3] = "WEB";
    headers[4] = COOKIE;
    headers[5] = RefreshTokenCookie.NAME + "=" + this.refreshToken;
    System.arraycopy(extraHeaders, 0, headers, 6, extraHeaders.length);
    return this.client.send("POST", REVOCATIONS, null, headers);
  }

  private HttpResponse<String> revokeMobile(String... extraHeaders) {
    return send("MOBILE", "{\"refreshToken\": \"" + this.refreshToken + "\"}", extraHeaders);
  }

  private HttpResponse<String> send(String platform, String body, String... extraHeaders) {
    String[] headers = new String[4 + extraHeaders.length];
    headers[0] = HttpHeaders.CONTENT_TYPE;
    headers[1] = "application/json";
    headers[2] = PLATFORM;
    headers[3] = platform;
    System.arraycopy(extraHeaders, 0, headers, 4, extraHeaders.length);
    return this.client.send("POST", REVOCATIONS, body, headers);
  }

  private static void assertRequestId(HttpResponse<String> response) {
    List<String> requestIds = response.headers().allValues("X-Request-Id");
    assertThat(requestIds).hasSize(1);
    assertThat(requestIds.get(0)).matches(UUID_V4);
    assertThat(UUID.fromString(requestIds.get(0)).version()).isEqualTo(4);
  }

  /** 삭제 쿠키는 발급과 같은 속성에 빈 값·{@code Max-Age=0}·과거 {@code Expires}다. */
  private static void assertDeleteCookie(HttpResponse<String> response) {
    List<String> cookies = response.headers().allValues(HttpHeaders.SET_COOKIE);
    assertThat(cookies).hasSize(1);
    String cookie = cookies.get(0);
    assertThat(cookie).startsWith(RefreshTokenCookie.NAME + "=;");
    assertThat(cookie).contains("Max-Age=0");
    assertThat(cookie).contains("Path=/").contains("Secure").contains("HttpOnly");
    assertThat(cookie).contains("SameSite=Strict").doesNotContain("Domain=");
    assertThat(cookie).contains("Expires=Thu, 1 Jan 1970 00:00:00 GMT");
  }

  private static void assertErrorCode(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    Map<String, Object> json = HttpTestClient.json(response);
    assertThat(json).containsOnlyKeys("code", "message");
    assertThat(json).containsEntry("code", code);
    assertRequestId(response);
  }

  // ---------- 204 ----------

  @Test
  void WEB_로그아웃은_204와_빈_body와_삭제_쿠키다() {
    HttpResponse<String> response = revokeWeb();

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(response.body()).isEmpty();
    assertDeleteCookie(response);
    assertRequestId(response);
  }

  @Test
  void MOBILE_로그아웃은_204이고_쿠키를_붙이지_않는다() {
    HttpResponse<String> response = revokeMobile();

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
    assertRequestId(response);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "ROTATED", "REVOKED"})
  void 식별된_토큰은_계열을_폐기하고_204다(String lookup) {
    doReturn(RefreshTokenLookupResult.valueOf(lookup)).when(this.refreshTokenStore).lookup(any());

    assertThat(revokeMobile().statusCode()).isEqualTo(204);
  }

  @Test
  void 이미_폐기된_계열은_멱등하게_204다() {
    doReturn(RefreshTokenLookupResult.FAMILY_REVOKED).when(this.refreshTokenStore).lookup(any());

    assertThat(revokeMobile().statusCode()).isEqualTo(204);
  }

  @Test
  void 계열_key가_사라졌어도_204다() {
    doReturn(RefreshFamilyRevocationResult.NOT_FOUND)
        .when(this.refreshTokenStore)
        .revokeFamily(anyLong(), anyString());

    assertThat(revokeMobile().statusCode()).isEqualTo(204);
  }

  // ---------- 401 ----------

  @Test
  void WEB의_401에도_삭제_쿠키가_남는다() {
    doReturn(RefreshTokenLookupResult.NOT_FOUND).when(this.refreshTokenStore).lookup(any());

    HttpResponse<String> response = revokeWeb();

    assertErrorCode(response, 401, "INVALID_REFRESH_TOKEN");
    // 공통 오류 처리가 응답을 만들어도 컨트롤러가 먼저 붙인 헤더는 지워지지 않는다.
    assertDeleteCookie(response);
  }

  @Test
  void WEB에_쿠키가_없어도_401에_삭제_쿠키가_남는다() {
    HttpResponse<String> response =
        this.client.send(
            "POST",
            REVOCATIONS,
            null,
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            "WEB");

    assertErrorCode(response, 401, "INVALID_REFRESH_TOKEN");
    assertDeleteCookie(response);
  }

  @Test
  void WEB의_위조된_토큰도_401에_삭제_쿠키가_남는다() {
    HttpResponse<String> response =
        this.client.send(
            "POST",
            REVOCATIONS,
            null,
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            "WEB",
            COOKIE,
            RefreshTokenCookie.NAME + "=not-a-real-token");

    assertErrorCode(response, 401, "INVALID_REFRESH_TOKEN");
    assertDeleteCookie(response);
  }

  @Test
  void 중복_Refresh_쿠키는_401이고_삭제_쿠키가_남는다() {
    HttpResponse<String> response =
        this.client.send(
            "POST",
            REVOCATIONS,
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

    assertErrorCode(response, 401, "INVALID_REFRESH_TOKEN");
    // WEB 판정과 전달 위치가 확정된 뒤의 실패이므로 쿠키를 지운다.
    assertDeleteCookie(response);
  }

  @Test
  void 빈_쿠키와_유효_쿠키가_함께_오면_401이고_저장소를_건드리지_않는다() {
    org.mockito.Mockito.clearInvocations(this.refreshTokenStore, this.refreshTokenVerifier);

    HttpResponse<String> response =
        this.client.send(
            "POST",
            REVOCATIONS,
            null,
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            "WEB",
            COOKIE,
            RefreshTokenCookie.NAME + "=; " + RefreshTokenCookie.NAME + "=" + this.refreshToken);

    // 빈 값을 먼저 걸러 내면 유효한 쪽 하나만 남아 정상 요청으로 둔갑한다.
    assertErrorCode(response, 401, "INVALID_REFRESH_TOKEN");
    // WEB 판정과 전달 위치가 확정된 뒤의 실패이므로 삭제 쿠키는 그대로 나간다.
    assertDeleteCookie(response);
    // 어떤 값도 JWT 검증기나 Redis로 넘어가지 않았다.
    org.mockito.Mockito.verifyNoInteractions(this.refreshTokenVerifier);
    org.mockito.Mockito.verifyNoInteractions(this.refreshTokenStore);
  }

  @Test
  void 빈_쿠키만_오면_401이고_삭제_쿠키가_남는다() {
    org.mockito.Mockito.clearInvocations(this.refreshTokenStore, this.refreshTokenVerifier);

    HttpResponse<String> response =
        this.client.send(
            "POST",
            REVOCATIONS,
            null,
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            "WEB",
            COOKIE,
            RefreshTokenCookie.NAME + "=");

    assertErrorCode(response, 401, "INVALID_REFRESH_TOKEN");
    assertDeleteCookie(response);
    org.mockito.Mockito.verifyNoInteractions(this.refreshTokenVerifier);
    org.mockito.Mockito.verifyNoInteractions(this.refreshTokenStore);
  }

  @Test
  void MOBILE의_401에는_쿠키가_없다() {
    HttpResponse<String> response = send("MOBILE", "{}");

    assertErrorCode(response, 401, "INVALID_REFRESH_TOKEN");
    assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"NOT_FOUND", "HASH_MISMATCH", "MISMATCHED"})
  void 상태가_식별되지_않으면_401이다(String lookup) {
    doReturn(RefreshTokenLookupResult.valueOf(lookup)).when(this.refreshTokenStore).lookup(any());

    assertErrorCode(revokeMobile(), 401, "INVALID_REFRESH_TOKEN");
  }

  // ---------- 503 ----------

  @Test
  void WEB의_503에도_삭제_쿠키가_남는다() {
    doReturn(RefreshTokenLookupResult.UNAVAILABLE).when(this.refreshTokenStore).lookup(any());

    HttpResponse<String> response = revokeWeb();

    assertErrorCode(response, 503, "AUTH_STATE_UNAVAILABLE");
    // 서버 상태를 정리하지 못했더라도 브라우저에 토큰을 남길 이유가 없다.
    assertDeleteCookie(response);
  }

  @Test
  void 폐기_명령_실패는_204가_아니라_503이다() {
    doReturn(RefreshFamilyRevocationResult.UNAVAILABLE)
        .when(this.refreshTokenStore)
        .revokeFamily(anyLong(), anyString());

    HttpResponse<String> response = revokeWeb();

    assertErrorCode(response, 503, "AUTH_STATE_UNAVAILABLE");
    assertDeleteCookie(response);
  }

  // ---------- 400 ----------

  @Test
  void 플랫폼_헤더가_없으면_400이고_삭제_쿠키가_없다() {
    HttpResponse<String> response =
        this.client.send(
            "POST",
            REVOCATIONS,
            null,
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            COOKIE,
            RefreshTokenCookie.NAME + "=" + this.refreshToken);

    assertErrorCode(response, 400, "INVALID_CLIENT_PLATFORM");
    // WEB 여부가 확정되지 않은 요청의 쿠키는 건드리지 않는다.
    assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "web", "DESKTOP"})
  void 지원하지_않는_플랫폼_값은_400이고_삭제_쿠키가_없다(String platform) {
    HttpResponse<String> response =
        this.client.send(
            "POST",
            REVOCATIONS,
            null,
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            platform,
            COOKIE,
            RefreshTokenCookie.NAME + "=" + this.refreshToken);

    assertErrorCode(response, 400, "INVALID_CLIENT_PLATFORM");
    assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  @Test
  void 전달_위치_충돌은_400이고_삭제_쿠키가_없다() {
    HttpResponse<String> response =
        this.client.send(
            "POST",
            REVOCATIONS,
            "{\"refreshToken\": \"" + this.refreshToken + "\"}",
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            PLATFORM,
            "WEB",
            COOKIE,
            RefreshTokenCookie.NAME + "=" + this.refreshToken);

    assertErrorCode(response, 400, "INVALID_CLIENT_PLATFORM");
    // 전달 경로가 확정되지 않았으므로 쿠키를 지우지 않는다.
    assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  @Test
  void MOBILE이_Refresh_쿠키를_보내면_400이다() {
    HttpResponse<String> response =
        revokeMobile(COOKIE, RefreshTokenCookie.NAME + "=" + this.refreshToken);

    assertErrorCode(response, 400, "INVALID_CLIENT_PLATFORM");
    assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {ALLOWED_ORIGIN, DISALLOWED_ORIGIN, "null"})
  void Origin이_있는_MOBILE_주장은_400이고_삭제_쿠키가_없다(String origin) {
    HttpResponse<String> response = revokeMobile(ORIGIN, origin);

    assertErrorCode(response, 400, "INVALID_CLIENT_PLATFORM");
    assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  @Test
  void 잘못된_JSON은_400_MALFORMED_REQUEST_BODY다() {
    assertErrorCode(send("MOBILE", "{\"refreshToken\": "), 400, "MALFORMED_REQUEST_BODY");
  }

  // ---------- CSRF·CORS 회귀 ----------

  @Test
  void Origin이_없는_WEB_로그아웃은_허용된다() {
    assertThat(revokeWeb().statusCode()).isEqualTo(204);
  }

  @Test
  void 허용_Origin의_WEB_로그아웃은_정상_처리된다() {
    HttpResponse<String> response = revokeWeb(ORIGIN, ALLOWED_ORIGIN);

    assertThat(response.statusCode()).isEqualTo(204);
    assertDeleteCookie(response);
  }

  @Test
  void 허용되지_않은_Origin의_WEB_요청은_업무_처리까지_도달하지_않는다() {
    HttpResponse<String> response = revokeWeb(ORIGIN, DISALLOWED_ORIGIN);

    assertThat(response.statusCode()).isEqualTo(403);
    // CORS에서 끝났으므로 업무 계층이 붙이는 삭제 쿠키도 없다.
    assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  @Test
  void 커스텀_헤더가_없는_cross_site_form_요청은_플랫폼_판정에서_거부된다() {
    HttpResponse<String> response =
        this.client.send(
            "POST",
            REVOCATIONS,
            "refreshToken=" + this.refreshToken,
            HttpHeaders.CONTENT_TYPE,
            "application/x-www-form-urlencoded",
            ORIGIN,
            ALLOWED_ORIGIN,
            COOKIE,
            RefreshTokenCookie.NAME + "=" + this.refreshToken);

    assertErrorCode(response, 400, "INVALID_CLIENT_PLATFORM");
    assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  // ---------- HTTP 회귀 ----------

  @Test
  void 지원하지_않는_method는_405다() {
    assertErrorCode(this.client.get(REVOCATIONS), 405, "UNSUPPORTED_HTTP_METHOD");
  }

  @Test
  void 오류_응답에_토큰도_식별자도_없다() {
    doReturn(RefreshTokenLookupResult.NOT_FOUND).when(this.refreshTokenStore).lookup(any());

    HttpResponse<String> response = revokeWeb();

    assertThat(response.body())
        .doesNotContain(this.refreshToken)
        .doesNotContain(this.username)
        .doesNotContain("familyId")
        .doesNotContain("Exception");
    // 삭제 쿠키에도 값이 실리지 않는다.
    assertThat(response.headers().firstValue(HttpHeaders.SET_COOKIE).orElseThrow())
        .doesNotContain(this.refreshToken);
  }
}
