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
  AUTH_STATE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),

  // 계정 (docs/product/api-spec.md 3.5절)
  // message에 어떤 값이 문제였는지 쓰지 않는다. 입력값·중복 대상·검증기 원문이 응답으로 새는 경로를 만들지 않기 위해서다.
  /** username 또는 email을 쓸 수 없다. 둘 중 어느 쪽인지 구분하지 않는다 (API 명세 3.2.1절). */
  ACCOUNT_IDENTIFIER_UNAVAILABLE(HttpStatus.CONFLICT, "사용할 수 없는 계정 정보입니다."),
  INVALID_ACCOUNT_INPUT(HttpStatus.UNPROCESSABLE_CONTENT, "계정 정보를 확인해 주세요."),
  INVALID_PASSWORD(HttpStatus.UNPROCESSABLE_CONTENT, "비밀번호 정책을 확인해 주세요."),
  /**
   * 계정 작업 토큰을 쓸 수 없다. 위조·만료·소비 완료·재발급으로 인한 무효화·용도 불일치·사용자 상태를 구분하지 않는다 (API 명세 3.4.1·3.6.2절).
   *
   * <p>{@code 400}인 이유는 요청이 들고 온 토큰 자체가 이 API의 입력으로 성립하지 않기 때문이다. 인증 실패({@code 401})도, 형식 위반({@code
   * 422})도 아니다.
   */
  ACTION_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "요청을 처리할 수 없습니다. 링크를 다시 요청해 주세요."),
  PASSWORD_POLICY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),

  // braille
  PATTERN_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "패턴 값이 허용 범위(0~63)를 벗어났습니다.");

  private final HttpStatus status;
  private final String message;

  public String getCode() {
    return name();
  }
}
