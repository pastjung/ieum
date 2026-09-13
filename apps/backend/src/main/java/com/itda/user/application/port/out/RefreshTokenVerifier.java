package com.itda.user.application.port.out;

/**
 * Refresh Token 검증 outbound port. 구현은 {@code user/infrastructure}에 둔다.
 *
 * <p>기대 종류·key ring·{@code typ}·{@code aud}를 이 port가 Refresh로 고정한다. 토큰에 실린 값으로 검증기나 ring을 고르지 않는다.
 */
public interface RefreshTokenVerifier {

  /**
   * @param compactToken 클라이언트가 보낸 원문. 신뢰하지 않는다
   * @return 실패 사유를 구분하지 않는 닫힌 결과
   */
  RefreshTokenVerification verify(String compactToken);
}
