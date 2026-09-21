package com.itda.global.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.itda.global.error.GlobalExceptionHandler;
import com.itda.support.log.CapturingAppender;
import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 실제 Tomcat에서 외부 요청 하나 = UUID v4 하나 = 완료 로그 하나 = 최종 상태 하나를 확인한다. */
@WebApiTest
@ExtendWith(OutputCaptureExtension.class)
class RequestTracingIntegrationTest {

  private static final String UUID_V4 = RequestTracingFilterTest.UUID_V4;
  private static final String[] JSON_BODY = {"Content-Type", "application/json"};
  private static final String[] ACCEPT_XML = {"Accept", "application/xml"};
  private static final String[] NONE = {};

  @LocalServerPort int port;

  private HttpTestClient http;
  private CapturingAppender completionLogs;

  @BeforeEach
  void setUp() {
    http = new HttpTestClient(port);
    completionLogs = CapturingAppender.attachTo(RequestTracingFilter.class);
  }

  @AfterEach
  void tearDown() {
    completionLogs.detach();
  }

  static Stream<Arguments> 요청_사례() {
    return Stream.of(
        arguments("정상 응답", "GET", "/api/v1/health", null, NONE, 200),
        arguments("body 없는 204", "GET", "/api/v1/test/no-content", null, NONE, 204),
        arguments("body 없는 202", "POST", "/api/v1/test/accepted", null, NONE, 202),
        arguments("잘못된 JSON", "POST", "/api/v1/test/body", "{", JSON_BODY, 400),
        arguments("없는 API", "GET", "/api/v1/does-not-exist", null, NONE, 404),
        arguments("지원하지 않는 method", "DELETE", "/api/v1/health", null, NONE, 405),
        arguments("정상 응답 표현 협상 실패", "GET", "/api/v1/health", null, ACCEPT_XML, 406),
        arguments(
            "지원하지 않는 Content-Type",
            "POST",
            "/api/v1/test/body",
            "x",
            new String[] {"Content-Type", "text/plain"},
            415),
        arguments(
            "DTO 검증 실패",
            "POST",
            "/api/v1/test/body",
            "{\"name\":\"\",\"count\":1}",
            JSON_BODY,
            422),
        arguments("예상하지 못한 예외", "GET", "/api/v1/test/failure", null, NONE, 500),
        arguments("JSON 불허 Accept의 500", "GET", "/api/v1/test/failure", null, ACCEPT_XML, 500),
        arguments(
            "filter 예외 → ERROR dispatch", "GET", "/api/v1/test/filter-failure", null, NONE, 500),
        arguments(
            "sendError → ERROR dispatch", "GET", "/api/v1/test/send-error/401", null, NONE, 401),
        arguments(
            "sendError + JSON 불허 Accept",
            "GET",
            "/api/v1/test/send-error/401",
            null,
            ACCEPT_XML,
            401),
        arguments("code 미정 상태", "GET", "/api/v1/test/send-error/413", null, NONE, 413),
        arguments("컨테이너가 먼저 거부한 TRACE", "TRACE", "/api/v1/health", null, NONE, 405));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("요청_사례")
  void 외부_요청_하나는_UUID_v4_하나와_최종_상태의_완료_로그_하나를_남긴다(
      String situation, String method, String path, String body, String[] headers, int status) {
    HttpResponse<String> response = http.send(method, path, body, headers);

    assertThat(response.statusCode()).isEqualTo(status);
    List<String> requestIds = response.headers().allValues("X-Request-Id");
    assertThat(requestIds).singleElement().asString().matches(UUID_V4);
    // ERROR dispatch를 거친 경우에도 로그의 MDC 값이 REQUEST에서 붙인 헤더와 같아야 찾아진다.
    // method·path는 ERROR dispatch의 GET·/error가 아니라 원래 요청 값이어야 한다.
    ILoggingEvent completion = awaitSingleCompletionLog(requestIds.getFirst());
    assertThat(completion.getFormattedMessage())
        .matches(
            "http_request method=%s path=%s status=%d durationMs=\\d+"
                .formatted(method, path, status));
  }

  @Test
  void 요청마다_서로_다른_ID를_만든다() {
    String first = requestIdOf(http.get("/api/v1/health"));
    String second = requestIdOf(http.get("/api/v1/health"));

    assertThat(first).isNotEqualTo(second);
  }

  @ParameterizedTest
  @ValueSource(strings = {"client-value", "3f2a9c1e-7b4d-4c2a-9e1f-0a1b2c3d4e5f"})
  void 외부_X_Request_Id는_형식과_관계없이_무시한다(String inbound) {
    HttpResponse<String> response = http.get("/api/v1/health", "X-Request-Id", inbound);

    assertThat(requestIdOf(response)).isNotEqualTo(inbound).matches(UUID_V4);
  }

  @Test
  void 요청_처리_중_MDC의_값은_응답_헤더와_같다() {
    HttpResponse<String> response = http.get("/api/v1/test/mdc");

    assertThat(HttpTestClient.json(response)).containsEntry("requestId", requestIdOf(response));
  }

  @Test
  void 명시적인_Set_Cookie_Location과_X_Request_Id가_함께_남는다() {
    HttpResponse<String> response = http.get("/api/v1/test/headers");

    assertThat(response.headers().firstValue("Set-Cookie"))
        .hasValue("test-cookie=1; Path=/; HttpOnly");
    assertThat(response.headers().firstValue("Location")).hasValue("/api/v1/test/somewhere");
    assertThat(requestIdOf(response)).matches(UUID_V4);
  }

  @Test
  void Controller는_X_Request_Id를_덮어쓰거나_추가할_수_없다() {
    HttpResponse<String> response = http.get("/api/v1/test/request-id-override");

    assertThat(response.headers().allValues("X-Request-Id"))
        .singleElement()
        .asString()
        .matches(UUID_V4);
  }

  @Test
  void response_reset_뒤에도_같은_X_Request_Id가_남는다() {
    HttpResponse<String> response = http.get("/api/v1/test/reset");

    assertThat(response.headers().firstValue("X-Before-Reset")).isEmpty();
    awaitSingleCompletionLog(requestIdOf(response));
  }

  @Test
  void 완료_로그는_query와_인증_헤더_값을_남기지_않는다() {
    HttpResponse<String> response =
        http.get(
            "/api/v1/health?email=leak@example.com&token=secret-token",
            "Authorization",
            "Bearer secret-token",
            "Cookie",
            "__Host-refreshToken=secret-refresh");

    assertThat(awaitSingleCompletionLog(requestIdOf(response)).getFormattedMessage())
        .matches("http_request method=GET path=/api/v1/health status=200 durationMs=\\d+");
  }

  @Test
  void 예상하지_못한_예외의_서버_오류_로그에도_같은_requestId가_붙는다() {
    CapturingAppender errorLogs = CapturingAppender.attachTo(GlobalExceptionHandler.class);
    try {
      String requestId = requestIdOf(http.get("/api/v1/test/failure"));

      await()
          .atMost(Duration.ofSeconds(3))
          .until(() -> !errorLogs.eventsWithMdc("requestId", requestId).isEmpty());
    } finally {
      errorLogs.detach();
    }
  }

  @Test
  void 콘솔_로그_줄에_requestId가_표시된다(CapturedOutput output) {
    String requestId = requestIdOf(http.get("/api/v1/health"));

    await()
        .atMost(Duration.ofSeconds(3))
        .until(() -> output.getOut().contains("[" + requestId + "] "));
  }

  private static String requestIdOf(HttpResponse<String> response) {
    return response.headers().firstValue("X-Request-Id").orElseThrow();
  }

  /** 완료 로그는 응답 전송과 겹쳐 기록될 수 있어 기다린다. 나타난 뒤에도 한 건으로 유지되는지 확인한다. */
  private ILoggingEvent awaitSingleCompletionLog(String requestId) {
    await()
        .atMost(Duration.ofSeconds(3))
        .until(() -> !completionLogs.eventsWithMdc("requestId", requestId).isEmpty());
    await()
        .during(Duration.ofMillis(300))
        .atMost(Duration.ofSeconds(2))
        .until(() -> completionLogs.eventsWithMdc("requestId", requestId).size() == 1);
    return completionLogs.eventsWithMdc("requestId", requestId).getFirst();
  }
}
