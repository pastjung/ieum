package com.itda.user.dto;

import com.itda.user.service.LoginService;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * {@code POST /api/v1/auth-sessions} 요청 body (API 명세 3.3.1절).
 *
 * <p>두 필드 모두 JSON 문자열만 받는다. Jackson 기본값은 숫자·불리언을 문자열로 바꿔 주는데, 그러면 타입 불일치가 형식 위반(422)으로 둔갑한다. 계약은 본문
 * 타입 불일치를 공통 {@code 400 MALFORMED_REQUEST_BODY}로 정했으므로 회원가입과 같은 문자열 전용 deserializer를 쓴다.
 *
 * <p>Bean Validation 애너테이션을 붙이지 않는다. 붙이면 실패가 공통 {@code INVALID_REQUEST_INPUT}으로 나가는데 이 API의 계약은
 * {@code 422 INVALID_ACCOUNT_INPUT}이다. 누락·형식 판정은 Service가 한 자리에서 한다.
 *
 * <p>플랫폼을 body로 받지 않는다. 전달 방식은 {@code X-Client-Platform} 헤더가 정한다.
 *
 * <p>{@code toString()}을 가린다 — 아이디와 비밀번호 원문이 들어 있다.
 */
public record LoginRequest(
    @JsonDeserialize(using = JsonStringOnlyDeserializer.class) String username,
    @JsonDeserialize(using = JsonStringOnlyDeserializer.class) String password) {

  public LoginService.Command toCommand() {
    return new LoginService.Command(this.username, this.password);
  }

  @Override
  public String toString() {
    return "LoginRequest[REDACTED]";
  }
}
