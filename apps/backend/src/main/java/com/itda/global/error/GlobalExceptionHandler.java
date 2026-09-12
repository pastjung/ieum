package com.itda.global.error;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * DispatcherServlet 안에서 난 예외를 공통 오류 형식으로 바꾼다 (docs/conventions/code/api.md "공통 HTTP 오류 code").
 *
 * <p>Spring MVC 표준 예외를 명시적으로 처리하는 이유: 처리하지 않으면 마지막 {@link Exception} 처리기가 400·404·405·415까지 500으로
 * 덮는다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusiness(
      BusinessException e, HttpServletRequest request) {
    return ErrorHttpResponseFactory.create(request, e.getErrorCode());
  }

  /** JSON 문법 오류, 빈 body, 필드 타입 불일치 — body를 요청 DTO로 읽지 못했다. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpServletRequest request) {
    return ErrorHttpResponseFactory.create(request, ErrorCode.MALFORMED_REQUEST_BODY);
  }

  /** body는 읽었지만 DTO 검증에 실패했다. 필드명·검증기 문구는 외부에 내보내지 않는다. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleInvalidBody(HttpServletRequest request) {
    return ErrorHttpResponseFactory.create(request, ErrorCode.INVALID_REQUEST_INPUT);
  }

  /**
   * path·query·header 값의 타입 변환 실패, 필수 값 누락, 파라미터 제약 위반.
   *
   * <p>한 메서드에 @Valid body와 제약 붙은 파라미터가 함께 있으면 Spring이 둘을 HandlerMethodValidationException 하나로 묶으므로
   * 이 처리기(400)로 온다.
   */
  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    MissingRequestHeaderException.class,
    HandlerMethodValidationException.class
  })
  public ResponseEntity<ErrorResponse> handleInvalidParameter(HttpServletRequest request) {
    return ErrorHttpResponseFactory.create(request, ErrorCode.INVALID_REQUEST_PARAMETER);
  }

  /**
   * Tomcat이 요청 파라미터를 해석하지 못한 경우 (개수 초과, 잘못된 percent-encoding). 처리하지 않으면 500이 된다.
   *
   * <p>413(크기 초과)은 아직 승인된 공통 code가 없으므로 상태만 유지하고 body를 비운다.
   */
  @ExceptionHandler(InvalidParameterException.class)
  public ResponseEntity<ErrorResponse> handleContainerParameter(
      InvalidParameterException e, HttpServletRequest request) {
    if (e.getErrorCode() == HttpStatus.BAD_REQUEST.value()) {
      return ErrorHttpResponseFactory.create(request, ErrorCode.INVALID_REQUEST_PARAMETER);
    }
    return ErrorHttpResponseFactory.withoutBody(HttpStatusCode.valueOf(e.getErrorCode()));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleUnsupportedContentType(
      HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
    // e.getHeaders()의 Accept는 서버가 받는 요청 Content-Type이다. 오류 body의 형식과는 별개다.
    return ErrorHttpResponseFactory.create(
        request, ErrorCode.UNSUPPORTED_CONTENT_TYPE, e.getHeaders());
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleUnsupportedMethod(
      HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
    return ErrorHttpResponseFactory.create(
        request, ErrorCode.UNSUPPORTED_HTTP_METHOD, e.getHeaders());
  }

  /** 기본 설정에서는 정적 리소스 handler가 NoResourceFoundException을 던진다. 설정이 바뀌어도 404가 유지되게 둘 다 받는다. */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<ErrorResponse> handleNoEndpoint(HttpServletRequest request) {
    return ErrorHttpResponseFactory.create(request, ErrorCode.ENDPOINT_NOT_FOUND);
  }

  /** 정상 응답을 Accept에 맞는 표현으로 만들 수 없다. 오류가 아니라 협상 실패이므로 406만 body 없이 보낸다. */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ErrorResponse> handleNotAcceptable() {
    return ErrorHttpResponseFactory.withoutBody(HttpStatus.NOT_ACCEPTABLE);
  }

  /** 원인은 서버 로그에만 남기고 외부에는 일반화된 code만 보낸다. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
    log.error("unexpected exception", e);
    return ErrorHttpResponseFactory.create(request, ErrorCode.INTERNAL_SERVER_ERROR);
  }
}
