package com.itda.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

/**
 * 거부 사유별 응답 규칙. 실제 Tomcat 흐름은 {@link CorsRejectionIntegrationTest}가 확인한다.
 *
 * <p>운영 설정은 모든 요청 header를 허용해 header 거부를 HTTP로 재현할 수 없다. 그래서 허용 header를 좁힌 설정으로 여기서 확인한다.
 */
class EmptyBodyCorsProcessorTest {

  private static final String ALLOWED_ORIGIN = "http://localhost:5173";

  private final EmptyBodyCorsProcessor processor = new EmptyBodyCorsProcessor();

  static Stream<Arguments> 거부_사례() {
    return Stream.of(
        arguments("허용되지 않은 Origin", preflight("https://not-allowed.example", "POST", null)),
        arguments("허용되지 않은 method", preflight(ALLOWED_ORIGIN, "PURGE", null)),
        arguments("허용되지 않은 header", preflight(ALLOWED_ORIGIN, "POST", "X-Not-Allowed")),
        arguments("형식이 깨진 Origin", preflight("http://[", "POST", null)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("거부_사례")
  void 거부_사유와_관계없이_403과_0바이트_body로_응답을_확정한다(String situation, MockHttpServletRequest request)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    // 앞선 추적 filter가 붙여 둔 헤더를 흉내 낸다. reset으로 지워지면 안 된다.
    response.setHeader("X-Request-Id", "set-by-tracing-filter");

    boolean valid = processor.processRequest(restrictedConfig(), request, response);

    assertThat(valid).isFalse();
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsByteArray()).isEmpty();
    assertThat(response.getContentLength()).isZero();
    assertThat(response.getContentType()).isNull();
    assertThat(response.isCommitted()).isTrue();
    assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    assertThat(response.getHeaders("X-Request-Id")).containsExactly("set-by-tracing-filter");
  }

  @Test
  void 허용된_preflight의_판정과_응답_헤더는_기본_구현과_같다() throws Exception {
    MockHttpServletResponse expected = new MockHttpServletResponse();
    MockHttpServletResponse actual = new MockHttpServletResponse();

    boolean expectedValid =
        new DefaultCorsProcessor()
            .processRequest(
                restrictedConfig(),
                preflight(ALLOWED_ORIGIN, "POST", "X-Client-Platform"),
                expected);
    boolean actualValid =
        processor.processRequest(
            restrictedConfig(), preflight(ALLOWED_ORIGIN, "POST", "X-Client-Platform"), actual);

    assertThat(actualValid).isTrue().isEqualTo(expectedValid);
    assertThat(actual.getStatus()).isEqualTo(expected.getStatus());
    assertThat(actual.getHeaderNames()).containsExactlyElementsOf(expected.getHeaderNames());
    for (String name : expected.getHeaderNames()) {
      assertThat(actual.getHeaders(name)).as(name).isEqualTo(expected.getHeaders(name));
    }
  }

  private static CorsConfiguration restrictedConfig() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(ALLOWED_ORIGIN));
    config.setAllowedMethods(List.of("GET", "POST"));
    config.setAllowedHeaders(List.of("X-Client-Platform"));
    config.setAllowCredentials(true);
    return config;
  }

  private static MockHttpServletRequest preflight(
      String origin, String requestMethod, String requestHeaders) {
    MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/resource");
    request.addHeader("Origin", origin);
    request.addHeader("Access-Control-Request-Method", requestMethod);
    if (requestHeaders != null) {
      request.addHeader("Access-Control-Request-Headers", requestHeaders);
    }
    return request;
  }
}
