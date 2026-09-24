package com.itda.support.web;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.error.ErrorCodeAttribute;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.MDC;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공통 오류·요청 추적 검증용 endpoint와 filter. 테스트 소스에만 있고 {@link WebApiTest}가 import할 때만 등록된다 — nested 클래스라
 * component scan에서는 제외된다.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(WebTestEndpoints.TestController.class)
public class WebTestEndpoints {

  /** 외부 응답·요청 로그에 새면 안 되는 내부 정보. 예외 원문에 일부러 넣는다. */
  public static final String INTERNAL_DETAIL =
      "SELECT password_hash FROM users WHERE email='leak@example.com' jdbc:mariadb://secret-host:3306";

  /** DispatcherServlet 앞에서 예외를 던지는 filter — 이후 도입될 보안 filter의 실패를 흉내 낸다. */
  @Bean
  FilterRegistrationBean<Filter> failingTestFilter() {
    Filter filter =
        (request, response, chain) -> {
          throw new IllegalStateException(INTERNAL_DETAIL);
        };
    FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
    registration.setUrlPatterns(List.of("/test/filter-failure"));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  /** CORS 거부가 Controller까지 가지 않는지 호출 횟수로 확인하는 endpoint (context path 제외). */
  public static final String CORS_PROBE_PATH = "/test/cors-probe";

  /** 이 query 이름이 붙은 요청만 요청 종료 시점의 MDC를 기록한다. 다른 테스트의 요청은 건드리지 않는다. */
  public static final String MDC_PROBE_PARAMETER = "mdcProbe";

  /** 요청 시작 때 listener가 MDC에 넣는 표식. 종료 시점에 보이면 같은 요청 thread의 MDC를 읽은 것이다. */
  public static final String MDC_PROBE_MARKER = "mdcProbeMarker";

  private static final AtomicInteger CORS_PROBE_CALLS = new AtomicInteger();
  private static final Map<String, Map<String, String>> MDC_AT_REQUEST_END =
      new ConcurrentHashMap<>();

  public static int corsProbeCalls() {
    return CORS_PROBE_CALLS.get();
  }

  public static Optional<Map<String, String>> mdcAtRequestEnd(String probe) {
    return Optional.ofNullable(MDC_AT_REQUEST_END.get(probe));
  }

  /**
   * Tomcat은 요청 listener를 filter chain·ERROR dispatch 전후에 같은 thread에서 부른다. 그래서 종료 시점의 MDC가 곧 요청 처리 뒤
   * thread에 남은 값이다.
   */
  @Bean
  ServletListenerRegistrationBean<ServletRequestListener> mdcAtRequestEndListener() {
    ServletRequestListener listener =
        new ServletRequestListener() {
          @Override
          public void requestInitialized(ServletRequestEvent event) {
            probeOf(event).ifPresent(probe -> MDC.put(MDC_PROBE_MARKER, probe));
          }

          @Override
          public void requestDestroyed(ServletRequestEvent event) {
            probeOf(event)
                .ifPresent(
                    probe -> {
                      Map<String, String> mdc = MDC.getCopyOfContextMap();
                      MDC_AT_REQUEST_END.put(probe, mdc == null ? Map.of() : Map.copyOf(mdc));
                      MDC.remove(MDC_PROBE_MARKER);
                    });
          }
        };
    return new ServletListenerRegistrationBean<>(listener);
  }

  /** 요청 parameter를 파싱하지 않는다. 종료 시점의 파싱이 다른 테스트의 잘못된 query에 부작용을 내지 않게 query string만 본다. */
  private static Optional<String> probeOf(ServletRequestEvent event) {
    if (!(event.getServletRequest() instanceof HttpServletRequest request)) {
      return Optional.empty();
    }
    String prefix = MDC_PROBE_PARAMETER + "=";
    String query = request.getQueryString();
    return query != null && query.startsWith(prefix)
        ? Optional.of(query.substring(prefix.length()))
        : Optional.empty();
  }

  @RestController
  @RequestMapping("/test")
  static class TestController {

    @GetMapping("/cors-probe")
    Map<String, Object> corsProbe() {
      CORS_PROBE_CALLS.incrementAndGet();
      return Map.of("ok", true);
    }

    record TestBody(@NotBlank String name, int count) {}

    @PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
    TestBody body(@Valid @RequestBody TestBody body) {
      return body;
    }

    @GetMapping("/items/{id}")
    Map<String, Object> item(@PathVariable Long id) {
      return Map.of("id", id);
    }

    @GetMapping("/query")
    Map<String, Object> query(@RequestParam int page) {
      return Map.of("page", page);
    }

    @GetMapping("/constrained")
    Map<String, Object> constrained(@RequestParam @Min(0) int page) {
      return Map.of("page", page);
    }

    @GetMapping("/header")
    Map<String, Object> header(@RequestHeader("X-Test-Header") String value) {
      return Map.of("value", value);
    }

    @GetMapping("/failure")
    Map<String, Object> failure() {
      throw new IllegalStateException(INTERNAL_DETAIL);
    }

    @GetMapping("/braille-pattern")
    Map<String, Object> braillePattern() {
      throw new BusinessException(ErrorCode.PATTERN_OUT_OF_RANGE);
    }

    @GetMapping("/send-error/{status}")
    void sendError(@PathVariable int status, HttpServletResponse response) throws IOException {
      response.sendError(status);
    }

    /** 필터가 의도한 ErrorCode를 남기고 sendError로 끝내는 상황을 흉내 낸다. */
    @GetMapping("/send-error/{status}/with-code/{code}")
    void sendErrorWithCode(
        @PathVariable int status,
        @PathVariable String code,
        HttpServletRequest request,
        HttpServletResponse response)
        throws IOException {
      ErrorCodeAttribute.set(request, ErrorCode.valueOf(code));
      response.sendError(status);
    }

    @GetMapping("/no-content")
    ResponseEntity<Void> noContent() {
      return ResponseEntity.noContent().build();
    }

    @PostMapping("/accepted")
    ResponseEntity<Void> accepted() {
      return ResponseEntity.accepted().build();
    }

    @GetMapping("/headers")
    ResponseEntity<Map<String, Object>> headers() {
      return ResponseEntity.ok()
          .header(HttpHeaders.SET_COOKIE, "test-cookie=1; Path=/; HttpOnly")
          .header(HttpHeaders.LOCATION, "/api/v1/test/somewhere")
          .body(Map.of("ok", true));
    }

    /** 요청 처리 중 MDC에 들어 있는 requestId를 그대로 돌려준다. */
    @GetMapping("/mdc")
    Map<String, Object> mdc() {
      Map<String, Object> body = new HashMap<>();
      body.put("requestId", MDC.get("requestId"));
      return body;
    }

    /** 후속 계층이 X-Request-Id를 덮어쓰려는 모든 경로를 한 번에 시도한다. */
    @GetMapping("/request-id-override")
    ResponseEntity<Map<String, Object>> requestIdOverride(HttpServletResponse response) {
      response.setHeader("X-Request-Id", "set-by-controller");
      response.addHeader("x-request-id", "added-by-controller");
      return ResponseEntity.ok().header("X-Request-Id", "entity-header").body(Map.of("ok", true));
    }

    /** 응답을 reset해 먼저 붙은 헤더를 모두 지운다. */
    @GetMapping("/reset")
    Map<String, Object> reset(HttpServletResponse response) {
      response.setHeader("X-Before-Reset", "removed");
      response.reset();
      return Map.of("ok", true);
    }
  }
}
