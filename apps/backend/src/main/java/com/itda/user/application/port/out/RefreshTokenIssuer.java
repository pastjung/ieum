package com.itda.user.application.port.out;

/**
 * Refresh Token 발급 outbound port. 구현은 {@code user/infrastructure}에 둔다.
 *
 * <p>Access와 같은 port로 합치지 않는다. key ring·{@code typ}·{@code aud}·필수 claim이 모두 다르고, 계약(API 명세 3.6.1절
 * 「종류 분리」)이 종류마다 다른 코드 경로를 타도록 요구한다.
 */
public interface RefreshTokenIssuer {

  /**
   * compact JWS와 함께 Redis 등록에 필요한 식별자·시각을 돌려준다.
   *
   * @return 반환값의 compact 토큰은 원문이다. 저장·로그 대상이 아니며 호출자에게 전달하기 위해서만 존재한다
   */
  IssuedRefreshToken issue(RefreshTokenClaims claims);
}
