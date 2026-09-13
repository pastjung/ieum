package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.support.security.AccessTokenTestKeys;
import com.itda.support.security.ActionTokenTestKeys;
import com.itda.support.security.RefreshTokenTestKeys;
import com.itda.user.application.port.out.AccessTokenClaims;
import com.itda.user.application.port.out.ActionTokenClaims;
import com.itda.user.application.port.out.ActionTokenVerification;
import com.itda.user.application.port.out.IssuedActionToken;
import com.itda.user.application.port.out.RefreshTokenClaims;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.UserRole;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 계정 작업 토큰 두 종류의 발급·검증을 고정한다.
 *
 * <p>두 용도가 ring 하나를 공유하므로 서명만으로는 구분되지 않는다. 그래서 {@code typ}·{@code aud}·{@code purpose}가 각각 다르고, 종류를
 * 바꿔 넣는 조합이 전부 실패해야 한다 (API 명세 3.6.1절 「종류 분리」).
 */
class NimbusActionTokenCodecTest {

  private static final String KID = "action-active-1";
  private static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L);
  private static final long USER_ID = 42L;
  private static final long EMAIL_TTL_SECONDS = 24 * 60 * 60L;
  private static final long RESET_TTL_SECONDS = 30 * 60L;

  private ActionKeyRing keyRing;
  private NimbusEmailVerificationTokenCodec emailCodec;
  private NimbusPasswordResetTokenCodec resetCodec;

  @BeforeEach
  void setUp() {
    this.keyRing = ActionKeyRing.from(ActionTokenTestKeys.properties(KID));
    this.emailCodec = emailCodecAt(NOW);
    this.resetCodec = resetCodecAt(NOW);
  }

  private NimbusEmailVerificationTokenCodec emailCodecAt(Instant instant) {
    return new NimbusEmailVerificationTokenCodec(
        this.keyRing, AccessTokenTestKeys.ISSUER, Clock.fixed(instant, ZoneOffset.UTC));
  }

  private NimbusPasswordResetTokenCodec resetCodecAt(Instant instant) {
    return new NimbusPasswordResetTokenCodec(
        this.keyRing, AccessTokenTestKeys.ISSUER, Clock.fixed(instant, ZoneOffset.UTC));
  }

  private IssuedActionToken issuedEmailToken() {
    return this.emailCodec.issue(new ActionTokenClaims(USER_ID));
  }

  private IssuedActionToken issuedResetToken() {
    return this.resetCodec.issue(new ActionTokenClaims(USER_ID));
  }

  private JWSHeader.Builder validEmailHeader() {
    return new JWSHeader.Builder(JWSAlgorithm.HS256)
        .type(new JOSEObjectType(NimbusEmailVerificationTokenCodec.TOKEN_TYPE))
        .keyID(KID);
  }

  private JWTClaimsSet.Builder validEmailClaims() {
    return new JWTClaimsSet.Builder()
        .issuer(AccessTokenTestKeys.ISSUER)
        .audience(NimbusEmailVerificationTokenCodec.AUDIENCE)
        .subject(Long.toString(USER_ID))
        .issueTime(Date.from(NOW))
        .expirationTime(Date.from(NOW.plusSeconds(EMAIL_TTL_SECONDS)))
        .jwtID(UUID.randomUUID().toString())
        .claim("purpose", AccountActionPurpose.EMAIL_VERIFICATION.name());
  }

  private String sign(JWSHeader header, JWTClaimsSet claims) {
    try {
      SignedJWT signedJwt = new SignedJWT(header, claims);
      signedJwt.sign(new MACSigner(this.keyRing.activeKey()));
      return signedJwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private void assertEmailRejected(String token) {
    assertThat(this.emailCodec.verify(token)).isInstanceOf(ActionTokenVerification.Invalid.class);
  }

  @Test
  void HS256이_아닌_alg를_거부한다() {
    // API 명세 3.6.1절 검증 순서 3단계. 같은 ring의 key로도 다른 alg면 통과해서는 안 된다.
    ActionKeyRing longKeyRing =
        ActionKeyRing.from(new ActionJwtProperties(KID, ActionTokenTestKeys.randomKey(64), ""));
    NimbusEmailVerificationTokenCodec longKeyCodec =
        new NimbusEmailVerificationTokenCodec(
            longKeyRing, AccessTokenTestKeys.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));
    String hs512Token;
    try {
      SignedJWT signedJwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.HS512)
                  .type(new JOSEObjectType(NimbusEmailVerificationTokenCodec.TOKEN_TYPE))
                  .keyID(KID)
                  .build(),
              validEmailClaims().build());
      signedJwt.sign(new MACSigner(longKeyRing.activeKey()));
      hs512Token = signedJwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }

    assertThat(longKeyCodec.verify(hs512Token)).isInstanceOf(ActionTokenVerification.Invalid.class);
  }

  // ---------- 발급 ----------

  @Test
  void 이메일_인증_토큰을_같은_설정으로_검증하면_통과한다() {
    IssuedActionToken token = issuedEmailToken();

    assertThat(this.emailCodec.verify(token.compactToken()))
        .isEqualTo(
            new ActionTokenVerification.Verified(
                USER_ID,
                AccountActionPurpose.EMAIL_VERIFICATION,
                token.jti(),
                // 검증기가 만든 해시가 발급기가 Redis에 넣은 값과 같아야 소비 조건이 성립한다.
                token.tokenHash(),
                token.issuedAtEpochSecond(),
                token.expiresAtEpochSecond()));
  }

  @Test
  void 비밀번호_재설정_토큰을_같은_설정으로_검증하면_통과한다() {
    IssuedActionToken token = issuedResetToken();

    assertThat(this.resetCodec.verify(token.compactToken()))
        .isEqualTo(
            new ActionTokenVerification.Verified(
                USER_ID,
                AccountActionPurpose.PASSWORD_RESET,
                token.jti(),
                token.tokenHash(),
                token.issuedAtEpochSecond(),
                token.expiresAtEpochSecond()));
  }

  @Test
  void 이메일_인증_토큰의_JOSE_Header와_payload가_계약과_같다() throws ParseException {
    SignedJWT parsed = SignedJWT.parse(issuedEmailToken().compactToken());

    assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
    assertThat(parsed.getHeader().getType().getType()).isEqualTo("itda-email-verification+jwt");
    assertThat(parsed.getHeader().getKeyID()).isEqualTo(KID);
    assertThat(parsed.getHeader().getJWKURL()).isNull();
    assertThat(parsed.getHeader().getX509CertURL()).isNull();

    JWTClaimsSet claims = parsed.getJWTClaimsSet();
    assertThat(claims.getIssuer()).isEqualTo("itda-backend-test");
    assertThat(claims.getAudience()).containsExactly("itda-email-verification");
    assertThat(claims.getSubject()).isEqualTo("42");
    assertThat(claims.getClaim("purpose")).isEqualTo("EMAIL_VERIFICATION");
    assertThat(UUID.fromString(claims.getJWTID()).version()).isEqualTo(4);
  }

  @Test
  void 비밀번호_재설정_토큰의_JOSE_Header와_payload가_계약과_같다() throws ParseException {
    SignedJWT parsed = SignedJWT.parse(issuedResetToken().compactToken());

    assertThat(parsed.getHeader().getType().getType()).isEqualTo("itda-password-reset+jwt");

    JWTClaimsSet claims = parsed.getJWTClaimsSet();
    assertThat(claims.getAudience()).containsExactly("itda-password-reset");
    assertThat(claims.getClaim("purpose")).isEqualTo("PASSWORD_RESET");
  }

  @Test
  void 계약이_금지한_claim을_싣지_않는다() throws ParseException {
    JWTClaimsSet claims = SignedJWT.parse(issuedEmailToken().compactToken()).getJWTClaimsSet();

    // 메일로 나가는 토큰이라 개인정보와 세대 값을 넣지 않는다 (API 명세 3.6.1절 claim 표).
    assertThat(claims.getClaim("authVersion")).isNull();
    assertThat(claims.getClaim("role")).isNull();
    assertThat(claims.getClaim("familyId")).isNull();
    assertThat(claims.getClaim("email")).isNull();
    assertThat(claims.getClaim("username")).isNull();
    assertThat(claims.getNotBeforeTime()).isNull();
  }

  @Test
  void 유효기간은_이메일_인증_24시간_비밀번호_재설정_30분이다() {
    IssuedActionToken email = issuedEmailToken();
    IssuedActionToken reset = issuedResetToken();

    assertThat(email.expiresAtEpochSecond() - email.issuedAtEpochSecond())
        .isEqualTo(EMAIL_TTL_SECONDS);
    assertThat(reset.expiresAtEpochSecond() - reset.issuedAtEpochSecond())
        .isEqualTo(RESET_TTL_SECONDS);
  }

  @Test
  void 같은_입력이라도_jti가_매번_다르다() {
    assertThat(issuedEmailToken().jti()).isNotEqualTo(issuedEmailToken().jti());
  }

  @Test
  void 발급_입력을_검증한다() {
    assertThatThrownBy(() -> new ActionTokenClaims(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ActionTokenClaims(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- 종류 분리 ----------

  @Test
  void 비밀번호_재설정_토큰을_이메일_인증_검증기에_넣으면_실패한다() {
    assertEmailRejected(issuedResetToken().compactToken());
  }

  @Test
  void 이메일_인증_토큰을_비밀번호_재설정_검증기에_넣으면_실패한다() {
    assertThat(this.resetCodec.verify(issuedEmailToken().compactToken()))
        .isInstanceOf(ActionTokenVerification.Invalid.class);
  }

  @Test
  void Access_토큰과_Refresh_토큰을_action_검증기에_넣으면_실패한다() {
    String accessToken =
        new NimbusAccessTokenCodec(
                AccessKeyRing.from(AccessTokenTestKeys.properties("access-active")),
                AccessTokenTestKeys.ISSUER,
                Clock.fixed(NOW, ZoneOffset.UTC))
            .issue(new AccessTokenClaims(USER_ID, UserRole.LEARNER, 0));
    String refreshToken =
        new NimbusRefreshTokenCodec(
                RefreshKeyRing.from(RefreshTokenTestKeys.properties("refresh-active")),
                AccessTokenTestKeys.ISSUER,
                Clock.fixed(NOW, ZoneOffset.UTC))
            .issue(new RefreshTokenClaims(USER_ID, UUID.randomUUID().toString(), 0))
            .compactToken();

    assertEmailRejected(accessToken);
    assertEmailRejected(refreshToken);
    assertThat(this.resetCodec.verify(accessToken))
        .isInstanceOf(ActionTokenVerification.Invalid.class);
    assertThat(this.resetCodec.verify(refreshToken))
        .isInstanceOf(ActionTokenVerification.Invalid.class);
  }

  @Test
  void action_토큰을_Access와_Refresh_검증기에_넣으면_실패한다() {
    NimbusAccessTokenCodec accessCodec =
        new NimbusAccessTokenCodec(
            AccessKeyRing.from(AccessTokenTestKeys.properties("access-active")),
            AccessTokenTestKeys.ISSUER,
            Clock.fixed(NOW, ZoneOffset.UTC));
    NimbusRefreshTokenCodec refreshCodec =
        new NimbusRefreshTokenCodec(
            RefreshKeyRing.from(RefreshTokenTestKeys.properties("refresh-active")),
            AccessTokenTestKeys.ISSUER,
            Clock.fixed(NOW, ZoneOffset.UTC));
    String emailToken = issuedEmailToken().compactToken();
    String resetToken = issuedResetToken().compactToken();

    assertThat(accessCodec.verify(emailToken))
        .isInstanceOf(com.itda.user.application.port.out.AccessTokenVerification.Invalid.class);
    assertThat(accessCodec.verify(resetToken))
        .isInstanceOf(com.itda.user.application.port.out.AccessTokenVerification.Invalid.class);
    assertThat(refreshCodec.verify(emailToken))
        .isInstanceOf(com.itda.user.application.port.out.RefreshTokenVerification.Invalid.class);
    assertThat(refreshCodec.verify(resetToken))
        .isInstanceOf(com.itda.user.application.port.out.RefreshTokenVerification.Invalid.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "itda-access+jwt",
        "itda-refresh+jwt",
        "itda-password-reset+jwt",
        "JWT",
        "itda-email-verification+JWT"
      })
  void 기대하지_않은_typ을_거부한다(String type) {
    assertEmailRejected(
        sign(
            validEmailHeader().type(new JOSEObjectType(type)).build(), validEmailClaims().build()));
  }

  @Test
  void purpose가_기대와_다르거나_없으면_거부한다() {
    assertEmailRejected(
        sign(
            validEmailHeader().build(),
            validEmailClaims().claim("purpose", "PASSWORD_RESET").build()));
    assertEmailRejected(
        sign(validEmailHeader().build(), validEmailClaims().claim("purpose", null).build()));
    assertEmailRejected(
        sign(validEmailHeader().build(), validEmailClaims().claim("purpose", 1).build()));
  }

  @Test
  void aud가_기대와_다르면_거부한다() {
    assertEmailRejected(
        sign(validEmailHeader().build(), validEmailClaims().audience("itda-api").build()));
    assertEmailRejected(
        sign(
            validEmailHeader().build(),
            validEmailClaims().audience(List.of("itda-email-verification", "itda-api")).build()));
  }

  // ---------- 헤더·서명 ----------

  @Test
  void ring에_없는_kid와_외부_key_조회_헤더를_거부한다() {
    assertEmailRejected(
        sign(validEmailHeader().keyID("access-active").build(), validEmailClaims().build()));
    assertEmailRejected(
        sign(
            validEmailHeader().jwkURL(URI.create("https://example.test/jwks")).build(),
            validEmailClaims().build()));
    assertEmailRejected(
        sign(
            validEmailHeader().x509CertURL(URI.create("https://example.test/cert")).build(),
            validEmailClaims().build()));
  }

  @Test
  void 서명을_변조하거나_다른_key로_서명하면_거부한다() {
    String token = issuedEmailToken().compactToken();
    assertEmailRejected(token.substring(0, token.lastIndexOf('.') + 1) + "AAAAAAAAAAAAAAAAAAAAAA");

    NimbusEmailVerificationTokenCodec otherCodec =
        new NimbusEmailVerificationTokenCodec(
            ActionKeyRing.from(ActionTokenTestKeys.properties(KID)),
            AccessTokenTestKeys.ISSUER,
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertEmailRejected(otherCodec.issue(new ActionTokenClaims(USER_ID)).compactToken());
  }

  @Test
  void retired_key로_서명된_토큰도_검증한다() {
    var retiredActive = ActionTokenTestKeys.properties("action-retired-1");
    String oldToken =
        new NimbusEmailVerificationTokenCodec(
                ActionKeyRing.from(retiredActive),
                AccessTokenTestKeys.ISSUER,
                Clock.fixed(NOW, ZoneOffset.UTC))
            .issue(new ActionTokenClaims(USER_ID))
            .compactToken();
    NimbusEmailVerificationTokenCodec rotated =
        new NimbusEmailVerificationTokenCodec(
            ActionKeyRing.from(
                new ActionJwtProperties(
                    "action-active-2",
                    ActionTokenTestKeys.randomKey(),
                    "action-retired-1:" + retiredActive.activeKey())),
            AccessTokenTestKeys.ISSUER,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(rotated.verify(oldToken)).isInstanceOf(ActionTokenVerification.Verified.class);
  }

  // ---------- claim·시각 ----------

  @ParameterizedTest
  @ValueSource(strings = {"", "not-a-token", "a.b", "a.b.c.d", "...."})
  void 형식이_아닌_값을_거부한다(String token) {
    assertEmailRejected(token);
  }

  @Test
  void null과_상한을_넘는_토큰을_거부한다() {
    assertEmailRejected(null);
    assertEmailRejected("a".repeat(NimbusActionTokenCodec.MAX_TOKEN_LENGTH + 1));
    assertThat(issuedEmailToken().compactToken().length())
        .isLessThanOrEqualTo(NimbusActionTokenCodec.MAX_TOKEN_LENGTH);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "007", "abc", " 42", "+42", "99999999999999999999"})
  void sub가_양수_BIGINT_정규_표현이_아니면_거부한다(String subject) {
    assertEmailRejected(
        sign(validEmailHeader().build(), validEmailClaims().subject(subject).build()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-a-uuid",
        "3F2A9C1E-1111-4111-8111-111111111111",
        "3f2a9c1e-1111-1111-8111-111111111111",
        "3f2a9c1e-1111-4111-c111-111111111111"
      })
  void jti가_canonical_UUID_v4가_아니면_거부한다(String jti) {
    assertEmailRejected(sign(validEmailHeader().build(), validEmailClaims().jwtID(jti).build()));
  }

  @Test
  void iat나_exp가_정수가_아니거나_없으면_거부한다() {
    assertEmailRejected(
        sign(validEmailHeader().build(), validEmailClaims().claim("exp", "1800086400").build()));
    assertEmailRejected(
        sign(validEmailHeader().build(), validEmailClaims().claim("iat", 1.8E9).build()));
    assertEmailRejected(
        sign(validEmailHeader().build(), validEmailClaims().expirationTime(null).build()));
    assertEmailRejected(
        sign(validEmailHeader().build(), validEmailClaims().issueTime(null).build()));
  }

  @Test
  void 유효기간이_계약과_다르면_거부한다() {
    assertEmailRejected(
        sign(
            validEmailHeader().build(),
            validEmailClaims()
                .expirationTime(Date.from(NOW.plusSeconds(EMAIL_TTL_SECONDS + 1)))
                .build()));
    assertEmailRejected(
        sign(
            validEmailHeader().build(),
            validEmailClaims()
                .expirationTime(Date.from(NOW.plusSeconds(EMAIL_TTL_SECONDS - 1)))
                .build()));
  }

  @Test
  void exp에_도달하면_만료이며_허용_오차가_없다() {
    String token = issuedEmailToken().compactToken();

    assertThat(emailCodecAt(NOW.plusSeconds(EMAIL_TTL_SECONDS - 1)).verify(token))
        .isInstanceOf(ActionTokenVerification.Verified.class);
    assertThat(emailCodecAt(NOW.plusSeconds(EMAIL_TTL_SECONDS)).verify(token))
        .isInstanceOf(ActionTokenVerification.Invalid.class);
  }

  @Test
  void 미래_iat는_30초까지만_허용한다() {
    String token = issuedEmailToken().compactToken();

    assertThat(emailCodecAt(NOW.minusSeconds(30)).verify(token))
        .isInstanceOf(ActionTokenVerification.Verified.class);
    assertThat(emailCodecAt(NOW.minusSeconds(31)).verify(token))
        .isInstanceOf(ActionTokenVerification.Invalid.class);
  }

  // ---------- 비노출 ----------

  @Test
  void 결과와_입력의_toString에_토큰도_사용자_정보도_없다() {
    IssuedActionToken token = issuedEmailToken();
    ActionTokenVerification verification = this.emailCodec.verify(token.compactToken());

    assertThat(token.toString())
        .isEqualTo("IssuedActionToken[REDACTED]")
        .doesNotContain(token.compactToken());
    assertThat(verification.toString())
        .isEqualTo("ActionTokenVerification.Verified[REDACTED]")
        .doesNotContain("42")
        .doesNotContain(token.jti());
    assertThat(new ActionTokenClaims(USER_ID).toString())
        .isEqualTo("ActionTokenClaims[REDACTED]")
        .doesNotContain("42");
  }

  @Test
  void 발급_결과도_불변식을_만족해야_만들어진다() {
    String uuid = UUID.randomUUID().toString();
    String hash = CompactTokenHasher.hash("a.b.c");

    assertThatThrownBy(
            () ->
                new IssuedActionToken(
                    " ", hash, uuid, AccountActionPurpose.EMAIL_VERIFICATION, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new IssuedActionToken(
                    "a.b.c", "짧은해시", uuid, AccountActionPurpose.EMAIL_VERIFICATION, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new IssuedActionToken(
                    "a.b.c", hash, "not-a-uuid", AccountActionPurpose.EMAIL_VERIFICATION, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IssuedActionToken("a.b.c", hash, uuid, null, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new IssuedActionToken(
                    "a.b.c", hash, uuid, AccountActionPurpose.EMAIL_VERIFICATION, 2, 2))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 발급_결과의_해시가_계약_규칙을_따른다() {
    IssuedActionToken token = issuedEmailToken();

    assertThat(token.tokenHash()).isEqualTo(CompactTokenHasher.hash(token.compactToken()));
    assertThat(token.tokenHash()).hasSize(43).doesNotContain(token.compactToken());
  }

  @Test
  void 검증_결과도_불변식을_만족해야_만들어진다() {
    String uuid = UUID.randomUUID().toString();
    String hash = issuedEmailToken().tokenHash();

    assertThatThrownBy(
            () ->
                new ActionTokenVerification.Verified(
                    0, AccountActionPurpose.EMAIL_VERIFICATION, uuid, hash, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ActionTokenVerification.Verified(1, null, uuid, hash, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ActionTokenVerification.Verified(
                    1, AccountActionPurpose.EMAIL_VERIFICATION, "not-a-uuid", hash, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    // 해시도 계약 형식(Base64URL 43자)을 만족해야 한다. Redis 키·조건에 그대로 실리는 값이다.
    assertThatThrownBy(
            () ->
                new ActionTokenVerification.Verified(
                    1, AccountActionPurpose.EMAIL_VERIFICATION, uuid, "short-hash", 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
