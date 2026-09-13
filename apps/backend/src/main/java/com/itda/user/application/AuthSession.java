package com.itda.user.application;

import java.time.LocalDateTime;

/**
 * 로그인 성공 결과. <b>플랫폼 중립</b>이다 (API 명세 3.3.1절).
 *
 * <p>WEB과 MOBILE은 같은 세션을 서로 다른 방식으로 전달할 뿐이므로 여기에는 둘의 차이가 없다. Refresh Token을 쿠키로 보낼지 body에 실을지는
 * presentation이 정한다 — application이 그 분기를 알면 전달 방식이 바뀔 때마다 유스케이스가 흔들린다.
 *
 * <p>시각은 UTC {@code LocalDateTime}이다. 발급기가 실제로 쓴 만료 시각을 그대로 옮기며 여기서 다시 계산하지 않는다.
 *
 * <p>{@code toString()}을 가린다 — Access·Refresh 원문이 들어 있다.
 */
public record AuthSession(
    String accessToken,
    LocalDateTime accessTokenExpiresAt,
    String refreshToken,
    LocalDateTime refreshTokenExpiresAt,
    UserSummary user) {

  @Override
  public String toString() {
    return "AuthSession[REDACTED]";
  }
}
