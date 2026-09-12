package com.itda.user.application.port.out;

/**
 * Access Token 발급 outbound port. 구현은 {@code user/infrastructure}에 둔다.
 *
 * <p>Access 전용이다. Refresh·action token은 key ring·{@code typ}·{@code aud}·필수 claim이 모두 다르므로 같은 port에
 * 종류를 파라미터로 넘기지 않는다 (API 명세 3.6.1절 「종류 분리」).
 */
public interface AccessTokenIssuer {

  /**
   * compact JWS 문자열을 만든다. {@code iat}·{@code exp}·{@code jti}는 서버가 생성한다.
   *
   * @return 반환값은 토큰 원문이다. 로그·예외 message·응답 헤더에 그대로 싣지 않는다
   */
  String issue(AccessTokenClaims claims);
}
