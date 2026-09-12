package com.itda.global.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DispatcherServlet 밖에서 끝난 오류를 공통 오류 형식으로 만든다: 필터에서 전파된 예외, sendError, TRACE처럼 컨테이너가 먼저 거부한 요청.
 * Tomcat은 이들을 /error로 다시 보낸다.
 *
 * <p>Boot 기본 BasicErrorController를 대체한다 (ErrorController 빈이 있으면 등록되지 않는다). 기본 구현은 {timestamp,
 * status, error, path} body와 브라우저용 HTML을 내보내 공통 계약과 어긋난다.
 *
 * <p>원래 오류 상태는 절대 바꾸지 않는다. 승인된 code가 없는 상태(403·413·429·503 등)는 body만 비운다
 * (docs/conventions/code/api.md "오류 상태 보존과 body 생략").
 */
@RestController
public class ErrorDispatchController implements ErrorController {

  private static final Map<Integer, ErrorCode> CODES_BY_STATUS =
      Map.of(
          400, ErrorCode.INVALID_REQUEST_PARAMETER,
          401, ErrorCode.UNAUTHORIZED,
          404, ErrorCode.ENDPOINT_NOT_FOUND,
          405, ErrorCode.UNSUPPORTED_HTTP_METHOD,
          415, ErrorCode.UNSUPPORTED_CONTENT_TYPE,
          500, ErrorCode.INTERNAL_SERVER_ERROR);

  @RequestMapping("${server.error.path:${error.path:/error}}")
  public ResponseEntity<ErrorResponse> handleErrorDispatch(HttpServletRequest request) {
    if (!(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status)) {
      // 오류 dispatch가 아니라 /error를 직접 호출했다. 이 경로는 공개 API가 아니다.
      return ErrorHttpResponseFactory.create(request, ErrorCode.ENDPOINT_NOT_FOUND);
    }
    // 오류를 낸 쪽이 code를 명시했으면 그것이 가장 정확하다. 상태만으로는 구분할 수 없는 경우가 있다.
    // 단, code가 뜻하는 상태와 실제 오류 상태가 같을 때만 쓴다. 어긋난 attribute를 따르면 code가 상태를
    // 바꿔 버려 "오류 상태는 그대로 둔다"는 규칙이 깨진다.
    ErrorCode code =
        ErrorCodeAttribute.find(request)
            .filter(intended -> intended.getStatus().value() == status)
            .orElseGet(() -> CODES_BY_STATUS.get(status));
    if (code == null) {
      return ErrorHttpResponseFactory.withoutBody(HttpStatusCode.valueOf(status));
    }
    return ErrorHttpResponseFactory.create(request, code);
  }
}
