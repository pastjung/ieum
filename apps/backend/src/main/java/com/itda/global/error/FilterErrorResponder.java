package com.itda.global.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/**
 * filter가 DispatcherServlet 밖에서 공통 오류 응답을 <b>직접</b> 쓴다.
 *
 * <p>보통은 {@code sendError}로 끝내고 ERROR dispatch가 body를 만들게 하면 된다({@link ErrorCodeAttribute}). 그런데 그
 * dispatch는 {@code /error}로 다시 들어가므로 MVC의 CORS 처리를 한 번 더 지난다. 허용 목록에 없는 {@code Origin}이 붙은 요청은 거기서
 * {@code 403}으로 바뀌어, filter가 정한 상태·code가 응답에 남지 않는다.
 *
 * <p>그래서 CORS 판정보다 앞에서 끝내야 하는 오류만 여기서 직접 쓴다. <b>body 조립 규칙은 복제하지 않는다</b> — 상태·Accept 협상·{@code
 * Content-Type} 결정은 {@link ErrorHttpResponseFactory}가 그대로 하고, 이 클래스는 그 결과를 servlet 응답으로 옮기기만 한다.
 *
 * <p>{@code X-Request-Id}는 건드리지 않는다. 더 앞선 추적 filter가 이미 붙여 둔 값이 그대로 나간다.
 */
public final class FilterErrorResponder {

  /**
   * 웹 계층 설정과 무관하게 같은 모양을 내도록 전용 인스턴스를 쓴다.
   *
   * <p>{@link ErrorResponse}는 문자열 두 개짜리 record라 직렬화에 별도 설정이 필요 없다.
   */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private FilterErrorResponder() {}

  /**
   * 오류 응답을 지금 이 자리에서 완성한다. 이후 filter chain을 진행하지 않는다.
   *
   * @param code 외부로 나갈 code. 요청 값·내부 예외는 어디에도 싣지 않는다
   */
  public static void send(HttpServletRequest request, HttpServletResponse response, ErrorCode code)
      throws IOException {
    ResponseEntity<ErrorResponse> entity = ErrorHttpResponseFactory.create(request, code);
    response.setStatus(entity.getStatusCode().value());
    ErrorResponse body = entity.getBody();
    if (body == null) {
      // Accept가 JSON을 허용하지 않는 요청이다. 상태만 남기고 body를 생략한다.
      response.setContentLength(0);
      return;
    }
    byte[] json = MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    response.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
    response.setContentLength(json.length);
    response.getOutputStream().write(json);
  }
}
