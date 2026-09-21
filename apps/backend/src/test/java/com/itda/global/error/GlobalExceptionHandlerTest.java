package com.itda.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * DispatcherServlet 안에서 난 예외의 상태·code·body·헤더 계약 (docs/conventions/code/api.md "공통 HTTP 오류 code").
 */
@WebApiTest
class GlobalExceptionHandlerTest {

  private static final String JSON = "application/json";

  @LocalServerPort int port;

  private HttpTestClient http;

  @BeforeEach
  void setUp() {
    http = new HttpTestClient(port);
  }

  static Stream<Arguments> 오류_사례() {
    String body = "/api/v1/test/body";
    return Stream.of(
        arguments("잘못된 JSON", "POST", body, "{\"name\":", ErrorCode.MALFORMED_REQUEST_BODY),
        arguments("필수 body 누락", "POST", body, "", ErrorCode.MALFORMED_REQUEST_BODY),
        arguments(
            "body 필드 타입 불일치",
            "POST",
            body,
            "{\"name\":\"a\",\"count\":\"abc\"}",
            ErrorCode.MALFORMED_REQUEST_BODY),
        arguments(
            "DTO 검증 실패",
            "POST",
            body,
            "{\"name\":\"\",\"count\":1}",
            ErrorCode.INVALID_REQUEST_INPUT),
        arguments(
            "path 타입 불일치",
            "GET",
            "/api/v1/test/items/abc",
            null,
            ErrorCode.INVALID_REQUEST_PARAMETER),
        arguments(
            "query 타입 불일치",
            "GET",
            "/api/v1/test/query?page=abc",
            null,
            ErrorCode.INVALID_REQUEST_PARAMETER),
        arguments(
            "필수 query 누락", "GET", "/api/v1/test/query", null, ErrorCode.INVALID_REQUEST_PARAMETER),
        arguments(
            "필수 header 누락",
            "GET",
            "/api/v1/test/header",
            null,
            ErrorCode.INVALID_REQUEST_PARAMETER),
        arguments(
            "파라미터 제약 위반",
            "GET",
            "/api/v1/test/constrained?page=-1",
            null,
            ErrorCode.INVALID_REQUEST_PARAMETER),
        arguments("없는 API 경로", "GET", "/api/v1/does-not-exist", null, ErrorCode.ENDPOINT_NOT_FOUND),
        arguments(
            "예상하지 못한 예외", "GET", "/api/v1/test/failure", null, ErrorCode.INTERNAL_SERVER_ERROR),
        arguments(
            "점자 패턴 범위 위반",
            "GET",
            "/api/v1/test/braille-pattern",
            null,
            ErrorCode.PATTERN_OUT_OF_RANGE));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("오류_사례")
  void 오류는_상태를_유지하고_code와_message만_담은_JSON을_보낸다(
      String situation, String method, String path, String body, ErrorCode expected) {
    HttpResponse<String> response =
        body == null
            ? http.send(method, path, null)
            : http.send(method, path, body, "Content-Type", JSON);

    assertThat(response.statusCode()).isEqualTo(expected.getStatus().value());
    assertThat(response.headers().firstValue("Content-Type")).hasValue(JSON);
    // 정확히 두 필드만 비교하므로 success·error·detail·필드명·검증기 문구·내부 예외 원문이 끼어들 수 없다.
    assertThat(HttpTestClient.json(response))
        .isEqualTo(Map.of("code", expected.getCode(), "message", expected.getMessage()));
  }

  @Test
  void 예상하지_못한_예외의_내부_정보는_body에_노출하지_않는다() {
    HttpResponse<String> response = http.get("/api/v1/test/failure");

    assertThat(response.body())
        .doesNotContain("SELECT", "secret-host", "leak@example.com", "IllegalStateException");
  }

  @Test
  void 요청_파라미터가_Tomcat_한도를_넘으면_400_INVALID_REQUEST_PARAMETER다() {
    StringBuilder path = new StringBuilder("/api/v1/test/query?page=1");
    for (int i = 0; i < 1000; i++) {
      path.append("&p").append(i).append("=1");
    }

    HttpResponse<String> response = http.get(path.toString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(HttpTestClient.json(response)).containsEntry("code", "INVALID_REQUEST_PARAMETER");
  }

  @Test
  void 지원하지_않는_method는_405와_Allow_헤더를_보낸다() {
    HttpResponse<String> response = http.send("DELETE", "/api/v1/health", null);

    assertThat(response.statusCode()).isEqualTo(405);
    assertThat(response.headers().firstValue("Allow"))
        .hasValueSatisfying(v -> assertThat(v).contains("GET"));
    assertThat(HttpTestClient.json(response)).containsEntry("code", "UNSUPPORTED_HTTP_METHOD");
  }

  @Test
  void 지원하지_않는_Content_Type은_415와_받는_요청_형식을_Accept로_알린다() {
    HttpResponse<String> response =
        http.send("POST", "/api/v1/test/body", "hello", "Content-Type", "text/plain");

    assertThat(response.statusCode()).isEqualTo(415);
    // 응답의 Accept는 서버가 받는 요청 Content-Type이고, 오류 body 자체는 JSON이다.
    assertThat(response.headers().firstValue("Accept")).hasValue(JSON);
    assertThat(response.headers().firstValue("Content-Type")).hasValue(JSON);
    assertThat(HttpTestClient.json(response)).containsEntry("code", "UNSUPPORTED_CONTENT_TYPE");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "*/*",
        "application/json",
        "application/*",
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
      })
  void JSON을_허용하는_Accept에는_원래_상태와_JSON_body를_보낸다(String accept) {
    HttpResponse<String> response = http.get("/api/v1/does-not-exist", "Accept", accept);

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(HttpTestClient.json(response)).containsEntry("code", "ENDPOINT_NOT_FOUND");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "/api/v1/does-not-exist | text/html                  | 404",
        "/api/v1/does-not-exist | application/xml            | 404",
        "/api/v1/does-not-exist | application/json;q=0, */* | 404",
        "/api/v1/does-not-exist | not a media type           | 404",
        "/api/v1/test/failure   | application/xml            | 500",
        "/api/v1/test/query     | application/xml            | 400"
      })
  void JSON을_허용하지_않는_Accept에는_원래_오류_상태를_유지하고_body만_생략한다(String path, String accept, int status) {
    HttpResponse<String> response = http.get(path, "Accept", accept);

    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().firstValue("Content-Type")).isEmpty();
  }

  @Test
  void 정상_응답을_Accept에_맞출_수_없을_때만_406을_body_없이_보낸다() {
    HttpResponse<String> response = http.get("/api/v1/health", "Accept", "application/xml");

    assertThat(response.statusCode()).isEqualTo(406);
    assertThat(response.body()).isEmpty();
  }
}
