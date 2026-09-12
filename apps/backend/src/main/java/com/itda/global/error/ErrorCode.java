package com.itda.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 외부 오류 code. 외부에 나가는 code 문자열은 enum 이름 그대로다 — 이름과 값이 따로 놀 여지를 없앤다.
 *
 * <p>공통 HTTP 오류의 원본은 docs/conventions/code/api.md "공통 HTTP 오류 code" 표다. 계약 문서에 없는 code를 여기에 먼저 추가하지
 * 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

  // 공통 HTTP 오류
  MALFORMED_REQUEST_BODY(HttpStatus.BAD_REQUEST, "요청 본문 형식을 확인해 주세요."),
  INVALID_REQUEST_PARAMETER(HttpStatus.BAD_REQUEST, "요청 값을 확인해 주세요."),
  ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 API를 찾을 수 없습니다."),
  UNSUPPORTED_HTTP_METHOD(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
  UNSUPPORTED_CONTENT_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
  INVALID_REQUEST_INPUT(HttpStatus.UNPROCESSABLE_CONTENT, "입력값을 확인해 주세요."),
  INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),

  // auth (docs/product/api-spec.md 3.5절)
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),

  // braille
  PATTERN_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "패턴 값이 허용 범위(0~63)를 벗어났습니다.");

  private final HttpStatus status;
  private final String message;

  public String getCode() {
    return name();
  }
}
