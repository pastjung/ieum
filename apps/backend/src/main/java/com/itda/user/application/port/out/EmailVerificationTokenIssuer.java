package com.itda.user.application.port.out;

/**
 * 이메일 인증 토큰 발급 outbound port (API 명세 3.6.1절).
 *
 * <p>용도별로 port를 나눈 이유는 계약이 "범용 발급기 하나에 종류를 파라미터로 넘기지 않으며, 종류가 다르면 다른 코드 경로를 타게 한다"고 정했기 때문이다. 비밀번호
 * 재설정 토큰이 필요한 자리에 이 port를 주입하면 컴파일 단계에서 드러난다.
 */
public interface EmailVerificationTokenIssuer {

  /** 유효기간 24시간, {@code purpose=EMAIL_VERIFICATION}인 토큰을 만든다. */
  IssuedActionToken issue(ActionTokenClaims claims);
}
