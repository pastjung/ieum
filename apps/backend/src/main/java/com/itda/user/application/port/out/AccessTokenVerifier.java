package com.itda.user.application.port.out;

/**
 * Access Token 검증 outbound port. 구현은 {@code user/infrastructure}에 둔다.
 *
 * <p>기대하는 토큰 종류·key ring·{@code typ}·{@code aud}는 이 port가 Access로 이미 고정한다. 토큰에 실린 값을 보고 검증기나 ring을
 * 고르지 않는다 (API 명세 3.6.1절 「검증 순서」).
 */
public interface AccessTokenVerifier {

  /**
   * @param compactToken Authorization 헤더에서 꺼낸 원문. 신뢰하지 않는다
   * @return 실패 사유를 구분하지 않는 닫힌 결과
   */
  AccessTokenVerification verify(String compactToken);
}
