package com.itda.user.application.port.out;

/**
 * 비밀번호 재설정 토큰 발급 outbound port (API 명세 3.6.1절).
 *
 * <p>이메일 인증 port와 나눈 이유는 {@link EmailVerificationTokenIssuer}의 설명과 같다.
 */
public interface PasswordResetTokenIssuer {

  /** 유효기간 30분, {@code purpose=PASSWORD_RESET}인 토큰을 만든다. */
  IssuedActionToken issue(ActionTokenClaims claims);
}
