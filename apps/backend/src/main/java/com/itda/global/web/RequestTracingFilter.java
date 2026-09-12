package com.itda.global.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 외부 요청마다 UUID v4 X-Request-Id 하나를 만들어 응답 헤더·MDC·완료 로그를 잇는다 (docs/product/api-spec.md 2.1절).
 *
 * <p>클라이언트가 보낸 X-Request-Id는 형식과 관계없이 무시한다. 응답 헤더는 이 필터만 쓴다 — 후속 계층의 setHeader·addHeader는 무시되고,
 * reset 뒤에도 같은 값이 다시 붙는다.
 *
 * <p>ERROR dispatch도 거친다. 필터에서 전파된 예외와 sendError는 Tomcat이 /error로 다시 보내므로 최종 응답 상태는 그 dispatch가 끝나야
 * 확정된다. 그래서 완료 로그는 요청당 한 번, 최종 상태로 남긴다. TRACE처럼 컨테이너가 필터보다 먼저 거부한 요청은 ERROR dispatch에서 이 필터를 처음
 * 만나므로 그때 식별자를 만든다.
 *
 * <p>완료 로그에는 method, query string을 뺀 path, status, 처리 시간만 남긴다. requestId는 MDC로만 전달해 로그 패턴이 붙인다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTracingFilter extends OncePerRequestFilter {

  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  public static final String MDC_KEY = "requestId";

  private static final String REQUEST_ID_ATTRIBUTE =
      RequestTracingFilter.class.getName() + ".requestId";
  private static final String START_NANOS_ATTRIBUTE =
      RequestTracingFilter.class.getName() + ".startNanos";
  private static final String COMPLETION_LOGGED_ATTRIBUTE =
      RequestTracingFilter.class.getName() + ".completionLogged";

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = obtainRequestId(request);
    response.setHeader(REQUEST_ID_HEADER, requestId);
    MDC.put(MDC_KEY, requestId);
    RequestIdOwningResponse owningResponse = new RequestIdOwningResponse(response, requestId);
    boolean failed = false;
    try {
      chain.doFilter(request, owningResponse);
    } catch (Throwable t) {
      failed = true;
      throw t;
    } finally {
      if (!errorDispatchFollows(request, response, owningResponse, failed)) {
        logCompletion(request, response);
      }
      MDC.remove(MDC_KEY);
    }
  }

  /** 같은 요청의 ERROR dispatch는 REQUEST에서 만든 값을 그대로 쓴다. 속성이 없을 때만 새로 만든다. */
  private static String obtainRequestId(HttpServletRequest request) {
    if (request.getAttribute(REQUEST_ID_ATTRIBUTE) instanceof String existing) {
      return existing;
    }
    String requestId = UUID.randomUUID().toString();
    request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
    request.setAttribute(START_NANOS_ATTRIBUTE, System.nanoTime());
    return requestId;
  }

  /**
   * 이 dispatch가 끝난 뒤 Tomcat이 /error로 다시 보낼지 판정한다. 그렇다면 완료 로그를 그 dispatch로 미룬다.
   *
   * <p>sendError 직후 Tomcat은 응답을 이미 commit된 것처럼 보고하므로 isCommitted로는 알 수 없다 — wrapper가 호출을 기록한다. 전파된
   * 예외는 응답이 아직 commit되지 않았을 때만 오류 페이지로 이어진다.
   */
  private static boolean errorDispatchFollows(
      HttpServletRequest request,
      HttpServletResponse response,
      RequestIdOwningResponse owningResponse,
      boolean failed) {
    return request.getDispatcherType() == DispatcherType.REQUEST
        && (owningResponse.isErrorSent() || (failed && !response.isCommitted()));
  }

  private static void logCompletion(HttpServletRequest request, HttpServletResponse response) {
    if (request.getAttribute(COMPLETION_LOGGED_ATTRIBUTE) != null) {
      return;
    }
    request.setAttribute(COMPLETION_LOGGED_ATTRIBUTE, Boolean.TRUE);
    long durationMs =
        TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - (long) request.getAttribute(START_NANOS_ATTRIBUTE));
    log.info(
        "http_request method={} path={} status={} durationMs={}",
        originalMethod(request),
        originalPath(request),
        response.getStatus(),
        durationMs);
  }

  /** ERROR dispatch에서 getMethod()는 GET을 돌려준다. 원래 method는 Servlet 6.1의 오류 속성에 있다. */
  private static Object originalMethod(HttpServletRequest request) {
    Object errorMethod = request.getAttribute(RequestDispatcher.ERROR_METHOD);
    return errorMethod != null ? errorMethod : request.getMethod();
  }

  /** ERROR dispatch에서 getRequestURI()는 /error를 돌려준다. getRequestURI는 query string을 포함하지 않는다. */
  private static Object originalPath(HttpServletRequest request) {
    Object errorUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    return errorUri != null ? errorUri : request.getRequestURI();
  }

  /** X-Request-Id를 이 필터만 쓰게 막고, 오류 페이지 dispatch를 부르는 sendError 호출을 기록한다. */
  private static final class RequestIdOwningResponse extends HttpServletResponseWrapper {

    private final String requestId;
    private boolean errorSent;

    RequestIdOwningResponse(HttpServletResponse response, String requestId) {
      super(response);
      this.requestId = requestId;
    }

    boolean isErrorSent() {
      return errorSent;
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
      errorSent = true;
      super.sendError(sc, msg);
    }

    @Override
    public void sendError(int sc) throws IOException {
      errorSent = true;
      super.sendError(sc);
    }

    @Override
    public void reset() {
      super.reset();
      super.setHeader(REQUEST_ID_HEADER, requestId);
    }

    @Override
    public void setHeader(String name, String value) {
      if (!isRequestIdHeader(name)) {
        super.setHeader(name, value);
      }
    }

    @Override
    public void addHeader(String name, String value) {
      if (!isRequestIdHeader(name)) {
        super.addHeader(name, value);
      }
    }

    @Override
    public void setIntHeader(String name, int value) {
      if (!isRequestIdHeader(name)) {
        super.setIntHeader(name, value);
      }
    }

    @Override
    public void addIntHeader(String name, int value) {
      if (!isRequestIdHeader(name)) {
        super.addIntHeader(name, value);
      }
    }

    @Override
    public void setDateHeader(String name, long date) {
      if (!isRequestIdHeader(name)) {
        super.setDateHeader(name, date);
      }
    }

    @Override
    public void addDateHeader(String name, long date) {
      if (!isRequestIdHeader(name)) {
        super.addDateHeader(name, date);
      }
    }

    private static boolean isRequestIdHeader(String name) {
      return REQUEST_ID_HEADER.equalsIgnoreCase(name);
    }
  }
}
