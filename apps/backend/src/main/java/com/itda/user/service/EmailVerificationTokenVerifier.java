package com.itda.user.service;

/**
 * 이메일 인증 토큰 검증 outbound port (API 명세 3.6.1절).
 *
 * <p>기대하는 토큰 종류·key ring·{@code typ}·{@code aud}·{@code purpose}를 이 port가 고정한다. 토큰에 실린 값으로 검증기를 고르지
 * 않는다.
 */
public interface EmailVerificationTokenVerifier {

  ActionTokenVerification verify(String compactToken);
}
