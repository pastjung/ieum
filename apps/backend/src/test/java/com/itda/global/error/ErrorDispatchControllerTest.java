package com.itda.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;

/** DispatcherServlet 밖에서 끝나 Tomcat이 /error로 보낸 오류의 계약. 원래 상태를 절대 바꾸지 않는다. */
@WebApiTest
class ErrorDispatchControllerTest {

  @LocalServerPort int port;

  private HttpTestClient http;

  @BeforeEach
  void setUp() {
    http = new HttpTestClient(port);
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST", "DELETE"})
  void error_경로를_직접_호출하면_404_ENDPOINT_NOT_FOUND다(String method) {
    HttpResponse<String> response = http.send(method, "/api/v1/error", null);

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(HttpTestClient.json(response))
        .isEqualTo(
            Map.of(
                "code",
                "ENDPOINT_NOT_FOUND",
                "message",
                ErrorCode.ENDPOINT_NOT_FOUND.getMessage()));
  }

  @ParameterizedTest
  @CsvSource({
    "400, INVALID_REQUEST_PARAMETER",
    "401, UNAUTHORIZED",
    "404, ENDPOINT_NOT_FOUND",
    "405, UNSUPPORTED_HTTP_METHOD",
    "415, UNSUPPORTED_CONTENT_TYPE",
    "500, INTERNAL_SERVER_ERROR"
  })
  void 승인된_code가_있는_오류_상태는_상태를_유지하고_JSON_body를_보낸다(int status, ErrorCode code) {
    HttpResponse<String> response = http.get("/api/v1/test/send-error/" + status);

    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
    assertThat(HttpTestClient.json(response))
        .isEqualTo(Map.of("code", code.getCode(), "message", code.getMessage()));
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 406, 413, 429, 503})
  void 승인된_code가_없는_오류_상태는_500으로_바꾸지_않고_body만_비운다(int status) {
    HttpResponse<String> response = http.get("/api/v1/test/send-error/" + status);

    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.body()).isEmpty();
  }

  @Test
  void filter에서_전파된_예외는_500_INTERNAL_SERVER_ERROR이고_내부_정보를_노출하지_않는다() {
    HttpResponse<String> response = http.get("/api/v1/test/filter-failure");

    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(HttpTestClient.json(response))
        .isEqualTo(
            Map.of(
                "code",
                "INTERNAL_SERVER_ERROR",
                "message",
                ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
  }

  @Test
  void 컨테이너가_거부한_TRACE는_405와_Allow_헤더_UNSUPPORTED_HTTP_METHOD를_보낸다() {
    HttpResponse<String> response = http.send("TRACE", "/api/v1/health", null);

    assertThat(response.statusCode()).isEqualTo(405);
    assertThat(response.headers().firstValue("Allow")).isPresent();
    assertThat(HttpTestClient.json(response)).containsEntry("code", "UNSUPPORTED_HTTP_METHOD");
  }

  @ParameterizedTest
  @CsvSource({
    "/api/v1/test/send-error/401, 401",
    "/api/v1/test/filter-failure, 500",
    "/api/v1/error, 404"
  })
  void 오류_dispatch에서도_JSON을_허용하지_않는_Accept에는_원래_상태를_유지하고_body만_생략한다(String path, int status) {
    HttpResponse<String> response = http.get(path, "Accept", "application/xml");

    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.body()).isEmpty();
  }
}
