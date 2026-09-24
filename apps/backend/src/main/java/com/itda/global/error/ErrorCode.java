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
  /**
   * 알 수 없는 계정·비밀번호 오류·로그인 제한·탈퇴를 <b>구분하지 않는다</b> (API 명세 3.5절).
   *
   * <p>원인을 나누면 응답만으로 "그 아이디가 존재하는가", "잠겨 있는가"를 알 수 있게 되어 계정 열거와 표적 공격의 단서가 된다.
   */
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호를 확인해 주세요."),
  /**
   * Refresh Token 없음·무효·만료·폐기·재사용 또는 {@code authVersion} 불일치를 <b>구분하지 않는다</b> (API 명세 3.5절).
   *
   * <p>원인을 나누면 "그 토큰이 저장소에 있었는가", "계열이 폐기됐는가" 같은 내부 상태가 응답만으로 드러난다. 재사용 탐지 결과도 마찬가지다 — 공격자가 어떤 토큰이
   * 아직 살아 있는지 탐색하는 신호가 된다.
   */
  INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),
  /** 자격 증명은 올바르지만 이메일 인증이 끝나지 않았다. 이때만 401과 구분되는 403이다 (API 명세 3.3.1절). */
  EMAIL_VERIFICATION_REQUIRED(HttpStatus.FORBIDDEN, "이메일 인증이 필요합니다."),
  /**
   * {@code X-Client-Platform}이 없거나 지원하지 않는 값이거나, {@code Origin}이 있는 요청이 MOBILE을 주장한다 (API 명세 3.3절).
   *
   * <p>{@code 400}인 이유는 요청이 어느 전달 방식을 쓸지 확정되지 않아 처리 자체를 시작할 수 없기 때문이다. 인증 실패가 아니다.
   */
  INVALID_CLIENT_PLATFORM(HttpStatus.BAD_REQUEST, "요청 형식을 확인해 주세요."),
  AUTH_STATE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),

  // 계정 (docs/product/api-spec.md 3.5절)
  // message에 어떤 값이 문제였는지 쓰지 않는다. 입력값·중복 대상·검증기 원문이 응답으로 새는 경로를 만들지 않기 위해서다.
  /** username 또는 email을 쓸 수 없다. 둘 중 어느 쪽인지 구분하지 않는다 (API 명세 3.2.1절). */
  ACCOUNT_IDENTIFIER_UNAVAILABLE(HttpStatus.CONFLICT, "사용할 수 없는 계정 정보입니다."),
  /** 탈퇴를 요청한 사용자의 현재 비밀번호가 저장된 비밀번호와 다르다 (API 명세 3.2.1·3.5절). */
  CURRENT_PASSWORD_MISMATCH(HttpStatus.FORBIDDEN, "현재 비밀번호를 확인해 주세요."),
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
  PATTERN_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "패턴 값이 허용 범위(0~63)를 벗어났습니다."),

  // learning completion
  LEARNING_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "학습 세션을 찾을 수 없습니다."),
  INVALID_ATTEMPT_ANALYSIS_REQUEST(
      HttpStatus.UNPROCESSABLE_CONTENT, "문제 풀이 분석 요청을 확인해 주세요."),
  LEARNING_PROBLEM_ALREADY_ANALYZED(
      HttpStatus.CONFLICT, "이미 분석이 완료된 문제입니다."),
  INVALID_COMPLETION_REQUEST(HttpStatus.UNPROCESSABLE_CONTENT, "학습 완료 요청을 확인해 주세요."),
  LEARNING_ANALYSIS_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "학습 결과를 분석할 수 없습니다. 잠시 후 다시 시도해 주세요.");

  private final HttpStatus status;
  private final String message;

  public String getCode() {
    return name();
  }
}
