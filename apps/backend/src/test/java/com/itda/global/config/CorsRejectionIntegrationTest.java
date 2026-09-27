package com.itda.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.support.web.WebTestEndpoints;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * CORS 거부 응답 계약 (Issue #15, docs/conventions/code/api.md "오류 상태 보존과 body 생략").
 *
 * <p>실제 Tomcat과 Security filter chain으로 확인한다. 거부는 두 곳에서 일어난다 — REQUEST dispatch는 Security chain의
 * {@code CorsFilter}, 그 filter가 건너뛰는 ERROR dispatch는 MVC handler mapping의 CORS interceptor다.
 */
@WebApiTest
class CorsRejectionIntegrationTest {

  private static final Pattern UUID_V4 =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  /** 기존 CORS 허용 목록의 값이다 ({@code WebConfig}). */
  private static final String ALLOWED_ORIGIN = "http://localhost:5173";

  private static final String DISALLOWED_ORIGIN = "https://not-allowed.example";

  /** 형식부터 JWT가 아닌 Access Token. CORS가 먼저 끝내지 않으면 인증 단계에서 401이 된다. */
  private static final String INVALID_BEARER = "Bearer not-a-jwt";

  private static final String ORIGIN = "Origin";
  private static final String REQUEST_METHOD = "Access-Control-Request-Method";
  private static final String REQUEST_HEADERS = "Access-Control-Request-Headers";
  private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
  private static final String PROBE = "/api/v1" + WebTestEndpoints.CORS_PROBE_PATH;

  @LocalServerPort int port;

  private HttpTestClient http;

  @BeforeEach
  void setUp() {
    http = new HttpTestClient(port);
  }

  static Stream<Arguments> CORS_거부_사례() {
    return Stream.of(
        arguments("허용되지 않은 Origin의 일반 요청", "GET", PROBE, headers(ORIGIN, DISALLOWED_ORIGIN)),
        arguments(
            "허용되지 않은 Origin의 preflight",
            "OPTIONS",
            PROBE,
            headers(ORIGIN, DISALLOWED_ORIGIN, REQUEST_METHOD, "GET")),
        arguments(
            "허용 Origin이지만 허용되지 않은 method의 preflight",
            "OPTIONS",
            PROBE,
            headers(ORIGIN, ALLOWED_ORIGIN, REQUEST_METHOD, "PURGE")),
        arguments("형식이 깨진 Origin", "GET", PROBE, headers(ORIGIN, "http://[")),
        arguments("Origin: null", "GET", PROBE, headers(ORIGIN, "null")),
        arguments(
            "JSON을 받는 Accept",
            "GET",
            PROBE,
            headers(ORIGIN, DISALLOWED_ORIGIN, "Accept", "application/json")),
        arguments(
            "HTML을 받는 Accept",
            "GET",
            PROBE,
            headers(ORIGIN, DISALLOWED_ORIGIN, "Accept", "text/html")),
        arguments(
            "ERROR dispatch — 컨테이너가 먼저 거부한 TRACE",
            "TRACE",
            "/api/v1/health",
            headers(ORIGIN, DISALLOWED_ORIGIN)),
        arguments(
            "ERROR dispatch — filter 예외",
            "GET",
            "/api/v1/test/filter-failure",
            headers(ORIGIN, DISALLOWED_ORIGIN)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("CORS_거부_사례")
  void CORS_거부는_403과_빈_body와_X_Request_Id_하나로_끝난다(
      String situation, String method, String path, String[] headers) {
    HttpResponse<String> response = http.send(method, path, null, headers);

    assertThat(response.statusCode()).isEqualTo(403);
    // 빈 문자열은 0바이트 body에서만 나온다. Spring 기본 문구·HTML·JSON 오류 body가 모두 여기서 걸린다.
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().allValues("Content-Length")).containsExactly("0");
    assertThat(response.headers().allValues("Content-Type")).isEmpty();
    assertThat(response.headers().allValues(ALLOW_ORIGIN)).isEmpty();
    assertThat(response.headers().allValues("X-Request-Id"))
        .singleElement()
        .asString()
        .matches(UUID_V4);
  }

  @Test
  void CORS_거부_요청은_Controller까지_가지_않는다() {
    int before = WebTestEndpoints.corsProbeCalls();

    http.send("GET", PROBE, null, ORIGIN, DISALLOWED_ORIGIN);
    http.send("OPTIONS", PROBE, null, ORIGIN, DISALLOWED_ORIGIN, REQUEST_METHOD, "GET");
    http.send("OPTIONS", PROBE, null, ORIGIN, ALLOWED_ORIGIN, REQUEST_METHOD, "PURGE");
    assertThat(WebTestEndpoints.corsProbeCalls()).isEqualTo(before);

    // 세는 endpoint가 실제로 호출되는 경로라는 대조군이다.
    assertThat(http.send("GET", PROBE, null, ORIGIN, ALLOWED_ORIGIN).statusCode()).isEqualTo(200);
    assertThat(WebTestEndpoints.corsProbeCalls()).isEqualTo(before + 1);
  }

  @Test
  void CORS_거부_요청마다_서로_다른_X_Request_Id를_만든다() {
    String first = requestIdOf(http.send("GET", PROBE, null, ORIGIN, DISALLOWED_ORIGIN));
    String second = requestIdOf(http.send("GET", PROBE, null, ORIGIN, DISALLOWED_ORIGIN));

    assertThat(first).isNotEqualTo(second);
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "OPTIONS", "TRACE"})
  void CORS_거부_요청이_끝나면_MDC에_requestId가_남지_않는다(String method) {
    String probe = UUID.randomUUID().toString();

    HttpResponse<String> response =
        http.send(
            method,
            PROBE + "?" + WebTestEndpoints.MDC_PROBE_PARAMETER + "=" + probe,
            null,
            ORIGIN,
            DISALLOWED_ORIGIN,
            REQUEST_METHOD,
            "GET");

    assertThat(response.statusCode()).isEqualTo(403);
    // 요청 종료 listener는 응답 전송과 겹쳐 실행될 수 있어 기다린다.
    await()
        .atMost(Duration.ofSeconds(3))
        .until(() -> WebTestEndpoints.mdcAtRequestEnd(probe).isPresent());
    // listener가 요청 시작 때 넣은 표식만 남아 있어야 한다. 표식이 보이므로 같은 요청 thread의 MDC를 본 것이다.
    assertThat(WebTestEndpoints.mdcAtRequestEnd(probe))
        .hasValue(Map.of(WebTestEndpoints.MDC_PROBE_MARKER, probe));
  }

  @Test
  void 허용_Origin의_일반_요청은_기존_CORS_헤더와_함께_처리된다() {
    HttpResponse<String> response = http.send("GET", PROBE, null, ORIGIN, ALLOWED_ORIGIN);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(HttpTestClient.json(response)).containsEntry("ok", true);
    assertThat(response.headers().allValues(ALLOW_ORIGIN)).containsExactly(ALLOWED_ORIGIN);
    assertThat(response.headers().allValues("Access-Control-Allow-Credentials"))
        .containsExactly("true");
    assertThat(response.headers().allValues("Vary")).contains(ORIGIN);
    assertThat(requestIdOf(response)).matches(UUID_V4);
  }

  /** 운영 웹이 같은 출처에서 API를 부를 때도 브라우저는 Origin을 붙인다 — 막히면 웹의 모든 API가 403이 된다. */
  @ParameterizedTest
  @ValueSource(strings = {"https://i-eum.site", "https://www.i-eum.site"})
  void 운영_웹_Origin의_요청은_허용된다(String origin) {
    HttpResponse<String> response = http.send("GET", PROBE, null, ORIGIN, origin);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().allValues(ALLOW_ORIGIN)).containsExactly(origin);
  }

  @Test
  void 허용_Origin의_preflight는_기존_허용_헤더를_돌려준다() {
    HttpResponse<String> preflight =
        http.send(
            "OPTIONS",
            "/api/v1/access-tokens",
            null,
            ORIGIN,
            ALLOWED_ORIGIN,
            REQUEST_METHOD,
            "POST",
            REQUEST_HEADERS,
            "X-Client-Platform");

    assertThat(preflight.statusCode()).isEqualTo(200);
    assertThat(preflight.body()).isEmpty();
    assertThat(preflight.headers().allValues(ALLOW_ORIGIN)).containsExactly(ALLOWED_ORIGIN);
    assertThat(preflight.headers().allValues("Access-Control-Allow-Methods"))
        .containsExactly("GET,POST,PUT,PATCH,DELETE,OPTIONS");
    assertThat(preflight.headers().allValues("Access-Control-Allow-Headers"))
        .containsExactly("X-Client-Platform");
    assertThat(preflight.headers().allValues("Access-Control-Allow-Credentials"))
        .containsExactly("true");
    assertThat(preflight.headers().allValues("X-Request-Id"))
        .singleElement()
        .asString()
        .matches(UUID_V4);
  }

  @Test
  void Origin_없는_서버_간_요청은_CORS_헤더_없이_처리된다() {
    int before = WebTestEndpoints.corsProbeCalls();

    HttpResponse<String> response = http.get(PROBE);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(HttpTestClient.json(response)).containsEntry("ok", true);
    assertThat(response.headers().allValues(ALLOW_ORIGIN)).isEmpty();
    assertThat(WebTestEndpoints.corsProbeCalls()).isEqualTo(before + 1);
    assertThat(requestIdOf(response)).matches(UUID_V4);
  }

  @Test
  void 허용_Origin의_ERROR_dispatch_오류는_공통_오류_body를_유지한다() {
    // ERROR dispatch도 MVC CORS interceptor를 지난다. 허용 Origin이면 거부가 아니므로 공통 오류 응답이 그대로 나가야 한다.
    HttpResponse<String> response =
        http.send("GET", "/api/v1/test/send-error/401", null, ORIGIN, ALLOWED_ORIGIN);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(HttpTestClient.json(response)).containsEntry("code", "UNAUTHORIZED");
    assertThat(requestIdOf(response)).matches(UUID_V4);
  }

  @Test
  void CORS_거부가_아닌_403은_업무_code_body를_유지한다() {
    // 모든 403을 비우지 않는다. CORS 판정을 통과한 요청의 업무 403은 {code, message}를 그대로 보낸다.
    String path = "/api/v1/test/send-error/403/with-code/EMAIL_VERIFICATION_REQUIRED";

    HttpResponse<String> withAllowedOrigin = http.send("GET", path, null, ORIGIN, ALLOWED_ORIGIN);
    HttpResponse<String> withoutOrigin = http.get(path);

    for (HttpResponse<String> response : new HttpResponse[] {withAllowedOrigin, withoutOrigin}) {
      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(HttpTestClient.json(response))
          .containsEntry("code", "EMAIL_VERIFICATION_REQUIRED");
      assertThat(requestIdOf(response)).matches(UUID_V4);
    }
  }

  // ---------- 보호 경로 chain (SecurityConfig#protectedApiSecurityFilterChain) ----------

  @Test
  void 보호_경로_GET의_CORS_거부는_잘못된_Access_Token보다_먼저_403으로_끝난다() {
    // CorsFilter가 AccessTokenAuthenticationFilter보다 앞이므로 토큰 불량의 401이 아니라 CORS 거부의 403이다.
    HttpResponse<String> response =
        http.send(
            "GET",
            "/api/v1/users/me",
            null,
            ORIGIN,
            DISALLOWED_ORIGIN,
            "Authorization",
            INVALID_BEARER);

    assertEmptyCorsRejection(response);
  }

  @Test
  void 보호_경로_POST의_CORS_거부는_인증과_요청_body_검증보다_먼저_403으로_끝난다() {
    // 플랫폼 filter는 인증 세션 세 경로에만 등록돼 있어 탈퇴 요청에는 X-Client-Platform을 싣지 않는다.
    HttpResponse<String> response =
        http.send(
            "POST",
            "/api/v1/users/me/withdrawals",
            "{\"currentPassword\":\"not-checked\"}",
            ORIGIN,
            DISALLOWED_ORIGIN,
            "Authorization",
            INVALID_BEARER,
            "Content-Type",
            "application/json");

    assertEmptyCorsRejection(response);
  }

  @Test
  void 보호_경로의_허용_Origin_인증_실패는_기존_401_오류_형식과_CORS_헤더를_유지한다() {
    HttpResponse<String> response =
        http.send("GET", "/api/v1/users/me", null, ORIGIN, ALLOWED_ORIGIN);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(HttpTestClient.json(response))
        .containsOnlyKeys("code", "message")
        .containsEntry("code", "UNAUTHORIZED");
    assertThat(response.headers().allValues(ALLOW_ORIGIN)).containsExactly(ALLOWED_ORIGIN);
    assertThat(response.headers().allValues("Access-Control-Allow-Credentials"))
        .containsExactly("true");
    assertThat(response.headers().allValues("X-Request-Id"))
        .singleElement()
        .asString()
        .matches(UUID_V4);
  }

  @Test
  void 보호_경로의_허용_preflight는_인증_없이_200으로_끝난다() {
    HttpResponse<String> preflight =
        http.send(
            "OPTIONS",
            "/api/v1/users/me",
            null,
            ORIGIN,
            ALLOWED_ORIGIN,
            REQUEST_METHOD,
            "GET",
            REQUEST_HEADERS,
            "Authorization");

    assertThat(preflight.statusCode()).isEqualTo(200);
    assertThat(preflight.body()).isEmpty();
    assertThat(preflight.headers().allValues(ALLOW_ORIGIN)).containsExactly(ALLOWED_ORIGIN);
    assertThat(preflight.headers().firstValue("Access-Control-Allow-Methods"))
        .hasValueSatisfying(methods -> assertThat(methods.split(",")).contains("GET"));
    assertThat(preflight.headers().firstValue("Access-Control-Allow-Headers"))
        .hasValueSatisfying(
            // header 이름은 대소문자를 구분하지 않는다.
            headers ->
                assertThat(headers.split(","))
                    .usingElementComparator(String.CASE_INSENSITIVE_ORDER)
                    .contains("Authorization"));
    assertThat(preflight.headers().allValues("Access-Control-Allow-Credentials"))
        .containsExactly("true");
    assertThat(preflight.headers().allValues("X-Request-Id"))
        .singleElement()
        .asString()
        .matches(UUID_V4);
  }

  /** CORS 거부 계약: 403, 0바이트 body, 표현 헤더·허용 헤더 없음, 추적 계층이 만든 요청 식별자 하나. */
  private static void assertEmptyCorsRejection(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().allValues("Content-Length")).containsExactly("0");
    assertThat(response.headers().allValues("Content-Type")).isEmpty();
    assertThat(response.headers().allValues(ALLOW_ORIGIN)).isEmpty();
    assertThat(response.headers().allValues("X-Request-Id"))
        .singleElement()
        .asString()
        .matches(UUID_V4);
  }

  private static String[] headers(String... pairs) {
    return pairs;
  }

  private static String requestIdOf(HttpResponse<String> response) {
    return response.headers().firstValue("X-Request-Id").orElseThrow();
  }
}
