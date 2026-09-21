package com.itda.global.error;

/**
 * 공통 오류 body. 최상위에 code와 message만 둔다 (docs/conventions/code/api.md "에러 응답").
 *
 * <p>순수 DTO다. HTTP 상태·헤더·Accept 처리는 {@link ErrorHttpResponseFactory}가 맡는다.
 */
public record ErrorResponse(String code, String message) {

  public static ErrorResponse of(ErrorCode errorCode) {
    return new ErrorResponse(errorCode.getCode(), errorCode.getMessage());
  }
}
