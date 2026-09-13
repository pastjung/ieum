package com.itda.user.infrastructure;

import com.itda.user.application.port.out.ActionTokenClaims;
import com.itda.user.application.port.out.ActionTokenVerification;
import com.itda.user.application.port.out.EmailVerificationTokenIssuer;
import com.itda.user.application.port.out.EmailVerificationTokenVerifier;
import com.itda.user.application.port.out.IssuedActionToken;
import com.itda.user.domain.AccountActionPurpose;
import java.time.Clock;
import java.time.Duration;

/**
 * 이메일 인증 토큰 발급·검증 어댑터 (API 명세 3.6.1절).
 *
 * <p>기대하는 {@code typ}·{@code aud}·{@code purpose}·유효기간을 이 클래스가 고정한다. 비밀번호 재설정 토큰을 이 검증기에 넣으면 {@code
 * typ}과 {@code aud}와 {@code purpose}가 모두 어긋나 거부된다.
 */
public class NimbusEmailVerificationTokenCodec
    implements EmailVerificationTokenIssuer, EmailVerificationTokenVerifier {

  /** API 명세 3.6.1절 Payload claim 표: 이메일 인증 유효기간 24시간. 계약 값이라 설정으로 열지 않는다. */
  static final Duration TOKEN_TTL = Duration.ofHours(24);

  static final String TOKEN_TYPE = "itda-email-verification+jwt";
  static final String AUDIENCE = "itda-email-verification";

  private final NimbusActionTokenCodec codec;

  public NimbusEmailVerificationTokenCodec(ActionKeyRing keyRing, String issuer, Clock clock) {
    this.codec =
        new NimbusActionTokenCodec(
            AccountActionPurpose.EMAIL_VERIFICATION,
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
