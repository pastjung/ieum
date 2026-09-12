package com.itda.support.web;

import com.itda.global.braille.BraillePattern;
import com.itda.global.error.ErrorCode;
import com.itda.global.error.ErrorCodeAttribute;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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

  @RestController
  @RequestMapping("/test")
  static class TestController {

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
      return Map.of("value", BraillePattern.of(BraillePattern.MAX_VALUE + 1).getValue());
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
