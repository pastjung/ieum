package com.itda.global.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * 필터가 {@code sendError}로 끝낸 요청의 의도한 오류 code를 ERROR dispatch까지 전달한다.
 *
 * <p>DispatcherServlet 밖에서 끝난 요청은 {@link GlobalExceptionHandler}를 거치지 못하고 상태 코드만 남는다. 그런데 상태만으로는
 * code를 정할 수 없는 경우가 있다 — 같은 503이라도 인증 상태 저장소 실패는 {@code AUTH_STATE_UNAVAILABLE}이지만 컨테이너가 만든 503은
 * 승인된 code가 없어 body를 비워야 한다. 그래서 code를 아는 쪽이 요청 속성으로 남기고, {@link ErrorDispatchController}는 그 속성이 있을
 * 때만 상태 표보다 우선한다.
 *
 * <p>속성 이름 문자열을 여러 패키지에 복제하지 않도록 이 클래스만 쓴다.
 */
public final class ErrorCodeAttribute {

  private static final String NAME = ErrorCodeAttribute.class.getName();

  private ErrorCodeAttribute() {}

  public static void set(HttpServletRequest request, ErrorCode code) {
    request.setAttribute(NAME, code);
  }

  static Optional<ErrorCode> find(HttpServletRequest request) {
    return request.getAttribute(NAME) instanceof ErrorCode code
        ? Optional.of(code)
        : Optional.empty();
  }
}
