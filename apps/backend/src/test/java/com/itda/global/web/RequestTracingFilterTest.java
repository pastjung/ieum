package com.itda.global.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.itda.support.log.CapturingAppender;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** dispatch 단위의 식별자·MDC·헤더·완료 로그 규칙. 실제 Tomcat 흐름은 RequestTracingIntegrationTest가 확인한다. */
class RequestTracingFilterTest {

  static final String UUID_V4 =
      "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

  private final RequestTracingFilter filter = new RequestTracingFilter();
  private CapturingAppender logs;

  @BeforeEach
  void setUp() {
    logs = CapturingAppender.attachTo(RequestTracingFilter.class);
  }

  @AfterEach
  void tearDown() {
    logs.detach();
    MDC.clear();
  }

  @Test
  void 요청마다_UUID_v4를_만들어_응답_헤더와_MDC에_같은_값으로_둔다() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> mdcDuringChain = new AtomicReference<>();

    filter.doFilter(
        get("/api/v1/health"), response, (req, res) -> mdcDuringChain.set(MDC.get("requestId")));

    String requestId = response.getHeader("X-Request-Id");
    assertThat(requestId).matches(UUID_V4);
    assertThat(mdcDuringChain.get()).isEqualTo(requestId);
    assertThat(MDC.get("requestId")).isNull();
  }

  @Test
  void chain이_예외를_던져도_MDC를_비운다() {
    FilterChain failing =
        (req, res) -> {
          throw new IllegalStateException("boom");
        };

    assertThatThrownBy(
            () -> filter.doFilter(get("/api/v1/x"), new MockHttpServletResponse(), failing))
        .isInstanceOf(IllegalStateException.class);
    assertThat(MDC.get("requestId")).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"client-value", "3f2a9c1e-7b4d-4c2a-9e1f-0a1b2c3d4e5f"})
  void 외부_X_Request_Id는_형식과_관계없이_무시한다(String inbound) throws Exception {
    MockHttpServletRequest request = get("/api/v1/health");
    request.addHeader("X-Request-Id", inbound);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(response.getHeader("X-Request-Id")).isNotEqualTo(inbound).matches(UUID_V4);
  }

  @Test
  void 후속_계층은_X_Request_Id를_바꿀_수_없고_다른_헤더는_그대로_둔다() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        get("/api/v1/x"),
        response,
        (req, res) -> {
          HttpServletResponse http = (HttpServletResponse) res;
          http.setHeader("X-Request-Id", "set");
          http.addHeader("x-request-id", "added");
          http.setIntHeader("X-Request-Id", 1);
          http.setHeader("Location", "/api/v1/somewhere");
          http.addHeader("Set-Cookie", "a=1; Path=/");
        });

    assertThat(response.getHeaders("X-Request-Id")).singleElement().asString().matches(UUID_V4);
    assertThat(response.getHeader("Location")).isEqualTo("/api/v1/somewhere");
    assertThat(response.getHeader("Set-Cookie")).isEqualTo("a=1; Path=/");
  }

  @Test
  void reset_뒤에도_같은_X_Request_Id가_남는다() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> mdcDuringChain = new AtomicReference<>();

    filter.doFilter(
        get("/api/v1/x"),
        response,
        (req, res) -> {
          mdcDuringChain.set(MDC.get("requestId"));
          ((HttpServletResponse) res).setHeader("X-Before-Reset", "1");
          res.reset();
        });

    assertThat(response.getHeader("X-Before-Reset")).isNull();
    assertThat(response.getHeader("X-Request-Id")).isEqualTo(mdcDuringChain.get());
  }

  @Test
  void sendError_뒤의_완료_로그는_ERROR_dispatch에서_같은_ID로_한_번만_남는다() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).sendError(401));
    String requestId = response.getHeader("X-Request-Id");
    assertThat(logs.events()).isEmpty();

    AtomicReference<String> mdcDuringErrorDispatch = new AtomicReference<>();
    toErrorDispatch(request, 401, "POST", "/api/v1/test");
    filter.doFilter(
        request, response, (req, res) -> mdcDuringErrorDispatch.set(MDC.get("requestId")));

    assertThat(mdcDuringErrorDispatch.get()).isEqualTo(requestId);
    assertThat(response.getHeaders("X-Request-Id")).containsExactly(requestId);
    List<ILoggingEvent> events = logs.eventsWithMdc("requestId", requestId);
    assertThat(events).singleElement();
    assertThat(events.getFirst().getFormattedMessage())
        .matches("http_request method=POST path=/api/v1/test status=401 durationMs=\\d+");
  }

  @Test
  void ERROR_dispatch에서_처음_만난_컨테이너_거부_요청은_새_ID를_만들고_한_번_기록한다() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/error");
    toErrorDispatch(request, 405, "TRACE", "/api/v1/health");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(405);

    filter.doFilter(request, response, (req, res) -> {});

    String requestId = response.getHeader("X-Request-Id");
    assertThat(requestId).matches(UUID_V4);
    assertThat(logs.eventsWithMdc("requestId", requestId))
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .matches("http_request method=TRACE path=/api/v1/health status=405 durationMs=\\d+");
  }

  @Test
  void 응답이_commit된_뒤_예외가_전파되면_ERROR_dispatch가_없으므로_바로_기록한다() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain commitThenFail =
        (req, res) -> {
          res.getWriter().write("partial");
          res.flushBuffer();
          throw new IllegalStateException("boom");
        };

    assertThatThrownBy(() -> filter.doFilter(get("/api/v1/x"), response, commitThenFail))
        .isInstanceOf(IllegalStateException.class);

    assertThat(logs.eventsWithMdc("requestId", response.getHeader("X-Request-Id")))
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .contains("status=200");
  }

  @Test
  void commit_전에_예외가_전파되면_완료_로그를_ERROR_dispatch로_미룬다() {
    FilterChain failing =
        (req, res) -> {
          throw new IllegalStateException("boom");
        };

    assertThatThrownBy(
            () -> filter.doFilter(get("/api/v1/x"), new MockHttpServletResponse(), failing))
        .isInstanceOf(IllegalStateException.class);

    assertThat(logs.events()).isEmpty();
  }

  @Test
  void 완료_로그에는_method_path_status_처리_시간만_남기고_query와_헤더_값은_남기지_않는다() throws Exception {
    MockHttpServletRequest request = get("/api/v1/health");
    request.setQueryString("email=leak@example.com&token=secret-token");
    request.addHeader("Authorization", "Bearer secret-token");
    request.addHeader("Cookie", "__Host-refreshToken=secret-refresh");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    ILoggingEvent event = logs.events().getFirst();
    assertThat(event.getFormattedMessage())
        .matches("http_request method=GET path=/api/v1/health status=200 durationMs=\\d+")
        .doesNotContain(response.getHeader("X-Request-Id"));
    assertThat(event.getMDCPropertyMap())
        .containsEntry("requestId", response.getHeader("X-Request-Id"));
    assertThat(event.getKeyValuePairs()).isNullOrEmpty();
  }

  private static MockHttpServletRequest get(String uri) {
    return new MockHttpServletRequest("GET", uri);
  }

  /** Tomcat이 오류 페이지로 넘길 때와 같은 dispatch 종류·속성을 만든다. */
  private static void toErrorDispatch(
      MockHttpServletRequest request, int status, String originalMethod, String originalUri) {
    request.setDispatcherType(DispatcherType.ERROR);
    request.setMethod("GET");
    request.setRequestURI("/api/v1/error");
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
    request.setAttribute(RequestDispatcher.ERROR_METHOD, originalMethod);
    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, originalUri);
  }
}
