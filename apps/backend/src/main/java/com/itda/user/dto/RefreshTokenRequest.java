package com.itda.user.dto;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 갱신·로그아웃 요청 body (API 명세 3.3.1절).
 *
 * <p>MOBILE만 이 body로 Refresh Token을 보낸다. WEB은 {@code __Host-refreshToken} 쿠키를 쓰며 이 필드에 값을 실으면 금지 위치
 * 전달이다. 어느 플랫폼이 어느 위치를 쓰는지는 {@code RefreshTokenDelivery}가 판정한다.
 *
 * <p>문자열만 받는다. Jackson 기본값은 숫자·불리언을 문자열로 바꿔 주는데, 그러면 타입 불일치가 업무 오류로 둔갑한다. 계약은 본문 타입 불일치를 공통 {@code
 * 400 MALFORMED_REQUEST_BODY}로 정했으므로 가입·로그인과 같은 문자열 전용 deserializer를 쓴다.
 *
 * <p>Bean Validation 애너테이션을 붙이지 않는다. 붙이면 실패가 공통 {@code INVALID_REQUEST_INPUT}으로 나가는데, 이 API의 계약은 허용
 * 위치에 토큰이 없으면 {@code 401 INVALID_REFRESH_TOKEN}, 금지 위치에 있으면 {@code 400 INVALID_CLIENT_PLATFORM}이다.
 *
 * <p>{@code toString()}을 가린다 — Refresh Token 원문이 들어 있다.
 */
public record RefreshTokenRequest(
    @JsonDeserialize(using = JsonStringOnlyDeserializer.class) String refreshToken) {

  @Override
  public String toString() {
    return "RefreshTokenRequest[REDACTED]";
  }
}
