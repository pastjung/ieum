package com.itda.user.service;

import com.itda.user.domain.AccountActionPurpose;
import java.time.Clock;
import java.time.Duration;

/**
 * 비밀번호 재설정 토큰 발급·검증 어댑터 (API 명세 3.6.1절).
 *
 * <p>이메일 인증 codec과 ring은 공유하지만 {@code typ}·{@code aud}·{@code purpose}·유효기간이 모두 다르고 구현하는 port도 다르다.
 * 두 종류를 바꿔 넣는 조합은 컴파일 또는 검증 단계에서 실패한다.
 */
public class NimbusPasswordResetTokenCodec
    implements PasswordResetTokenIssuer, PasswordResetTokenVerifier {

  /** API 명세 3.6.1절 Payload claim 표: 비밀번호 재설정 유효기간 30분. */
  static final Duration TOKEN_TTL = Duration.ofMinutes(30);

  static final String TOKEN_TYPE = "itda-password-reset+jwt";
  static final String AUDIENCE = "itda-password-reset";

  private final NimbusActionTokenCodec codec;

  public NimbusPasswordResetTokenCodec(ActionKeyRing keyRing, String issuer, Clock clock) {
    this.codec =
        new NimbusActionTokenCodec(
            AccountActionPurpose.PASSWORD_RESET,
            TOKEN_TYPE,
            AUDIENCE,
            TOKEN_TTL,
            keyRing,
            issuer,
            clock);
  }

  @Override
  public IssuedActionToken issue(ActionTokenClaims claims) {
    return this.codec.issue(claims);
  }

  @Override
  public ActionTokenVerification verify(String compactToken) {
    return this.codec.verify(compactToken);
  }
}
