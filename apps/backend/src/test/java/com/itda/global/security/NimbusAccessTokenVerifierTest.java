package com.itda.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.support.security.AccessTokenTestKeys;
import com.itda.user.domain.UserRole;
import com.itda.user.service.AccessTokenIssuer;
import com.itda.user.service.NimbusAccessTokenIssuer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
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

class NimbusAccessTokenVerifierTest {

  private static final String KID = "active-1";
  private static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L);
  private static final long USER_ID = 42L;

  private AccessKeyRing keyRing;
  private NimbusAccessTokenIssuer issuer;
  private NimbusAccessTokenVerifier verifier;

  @BeforeEach
  void setUp() {
    this.keyRing = AccessKeyRing.from(AccessTokenTestKeys.properties(KID));
    this.issuer =
        new NimbusAccessTokenIssuer(
            this.keyRing, AccessTokenTestKeys.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));
    this.verifier = verifierAt(NOW);
  }

  private NimbusAccessTokenVerifier verifierAt(Instant instant) {
    return new NimbusAccessTokenVerifier(
        this.keyRing, AccessTokenTestKeys.ISSUER, Clock.fixed(instant, ZoneOffset.UTC));
  }

  private String issuedToken() {
    return this.issuer
        .issue(new AccessTokenIssuer.Claims(USER_ID, UserRole.LEARNER, 0))
        .compactToken();
  }

  private JWSHeader.Builder validHeader() {
    return new JWSHeader.Builder(JWSAlgorithm.HS256)
        .type(new JOSEObjectType(AccessJwtContract.TOKEN_TYPE))
        .keyID(KID);
  }

  private JWTClaimsSet.Builder validClaims() {
    return new JWTClaimsSet.Builder()
        .issuer(AccessTokenTestKeys.ISSUER)
        .audience(AccessJwtContract.AUDIENCE)
        .subject(Long.toString(USER_ID))
        .issueTime(Date.from(NOW))
        .expirationTime(Date.from(NOW.plusSeconds(AccessJwtContract.TOKEN_TTL.toSeconds())))
        .jwtID(UUID.randomUUID().toString())
        .claim("authVersion", 0L)
        .claim("role", UserRole.LEARNER.name());
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

  private void assertRejected(String token) {
    assertThat(this.verifier.verify(token))
        .isInstanceOf(AccessTokenVerifier.Verification.Invalid.class);
  }

  // ---------- 길이·형식 ----------

  @Test
  void 상한을_넘는_토큰은_파싱하지_않고_거부한다() {
    assertRejected("a".repeat(NimbusAccessTokenVerifier.MAX_TOKEN_LENGTH + 1));
    assertThat(issuedToken().length())
        .isLessThanOrEqualTo(NimbusAccessTokenVerifier.MAX_TOKEN_LENGTH);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not-a-token", "a.b", "a.b.c.d", "....", "eyJhbGciOiJIUzI1NiJ9"})
  void 형식이_아닌_값을_거부한다(String token) {
    assertRejected(token);
  }

  @Test
  void null_토큰을_거부한다() {
    assertRejected(null);
  }

  // ---------- 서명 ----------

  @Test
  void 서명을_변조하면_거부한다() {
    String token = issuedToken();
    String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "AAAAAAAAAAAAAAAAAAAAAA";

    assertRejected(tampered);
  }

  @Test
  void 다른_key로_서명한_토큰을_거부한다() {
    AccessKeyRing otherRing = AccessKeyRing.from(AccessTokenTestKeys.properties(KID));
    NimbusAccessTokenIssuer otherIssuer =
        new NimbusAccessTokenIssuer(
            otherRing, AccessTokenTestKeys.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));

    assertRejected(
        otherIssuer
            .issue(new AccessTokenIssuer.Claims(USER_ID, UserRole.LEARNER, 0))
            .compactToken());
  }

  @Test
  void retired_key로_서명된_토큰도_검증한다() {
    AccessJwtProperties retiredActive = AccessTokenTestKeys.properties("retired-1");
    AccessKeyRing oldRing = AccessKeyRing.from(retiredActive);
    String oldToken =
        new NimbusAccessTokenIssuer(
                oldRing, AccessTokenTestKeys.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC))
            .issue(new AccessTokenIssuer.Claims(USER_ID, UserRole.ADMIN, 3))
            .compactToken();

    AccessKeyRing rotated =
        AccessKeyRing.from(
            new AccessJwtProperties(
                AccessTokenTestKeys.ISSUER,
                "active-2",
                AccessTokenTestKeys.randomKey(),
                "retired-1:" + retiredActive.activeKey()));
    NimbusAccessTokenVerifier rotatedVerifier =
        new NimbusAccessTokenVerifier(
            rotated, AccessTokenTestKeys.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(rotatedVerifier.verify(oldToken))
        .isEqualTo(new AccessTokenVerifier.Verification.Verified(USER_ID, UserRole.ADMIN, 3));
  }

  // ---------- JOSE Header ----------

  @Test
  void HS256이_아닌_alg를_거부한다() {
    AccessKeyRing longKeyRing =
        AccessKeyRing.from(
            new AccessJwtProperties(
                AccessTokenTestKeys.ISSUER, KID, AccessTokenTestKeys.randomKey(64), ""));
    NimbusAccessTokenVerifier longKeyVerifier =
        new NimbusAccessTokenVerifier(
            longKeyRing, AccessTokenTestKeys.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));
    String hs512Token;
    try {
      SignedJWT signedJwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.HS512)
                  .type(new JOSEObjectType(AccessJwtContract.TOKEN_TYPE))
                  .keyID(KID)
                  .build(),
              validClaims().build());
      signedJwt.sign(new MACSigner(longKeyRing.activeKey()));
      hs512Token = signedJwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }

    assertThat(longKeyVerifier.verify(hs512Token))
        .isInstanceOf(AccessTokenVerifier.Verification.Invalid.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "itda-refresh+jwt",
        "itda-email-verification+jwt",
        "itda-password-reset+jwt",
        "JWT",
        "itda-access+JWT"
      })
  void 기대하지_않은_typ을_거부한다(String type) {
    assertRejected(
        sign(validHeader().type(new JOSEObjectType(type)).build(), validClaims().build()));
  }

  @Test
  void typ이_없으면_거부한다() {
    assertRejected(
        sign(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KID).build(), validClaims().build()));
  }

  @Test
  void kid가_없으면_거부한다() {
    assertRejected(
        sign(
            new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(new JOSEObjectType(AccessJwtContract.TOKEN_TYPE))
                .build(),
            validClaims().build()));
  }

  @Test
  void ring에_없는_kid를_거부한다() {
    assertRejected(sign(validHeader().keyID("unknown-kid").build(), validClaims().build()));
  }

  @Test
  void 외부_key_조회를_유발하는_헤더를_거부한다() {
    assertRejected(
        sign(
            validHeader().jwkURL(URI.create("https://example.test/jwks")).build(),
            validClaims().build()));
    assertRejected(
        sign(
            validHeader().x509CertURL(URI.create("https://example.test/cert")).build(),
            validClaims().build()));
  }

  // ---------- claim ----------

  @Test
  void iss가_다르면_거부한다() {
    assertRejected(sign(validHeader().build(), validClaims().issuer("itda-backend-prod").build()));
    assertRejected(sign(validHeader().build(), validClaims().issuer(null).build()));
  }

  @Test
  void aud가_itda_api가_아니면_거부한다() {
    assertRejected(sign(validHeader().build(), validClaims().audience("itda-refresh").build()));
    assertRejected(sign(validHeader().build(), validClaims().audience(List.of()).build()));
  }

  @Test
  void 추가_audience가_섞인_배열을_거부한다() {
    assertRejected(
        sign(validHeader().build(), validClaims().audience(List.of("itda-api", "other")).build()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "007", "abc", " 42", "42 ", "+42", "99999999999999999999"})
  void sub가_양수_BIGINT_정규_표현이_아니면_거부한다(String subject) {
    assertRejected(sign(validHeader().build(), validClaims().subject(subject).build()));
  }

  @Test
  void sub가_없으면_거부한다() {
    assertRejected(sign(validHeader().build(), validClaims().subject(null).build()));
  }

  @Test
  void iat나_exp가_정수가_아니면_거부한다() {
    assertRejected(sign(validHeader().build(), validClaims().claim("exp", "1800000900").build()));
    assertRejected(sign(validHeader().build(), validClaims().claim("iat", "1800000000").build()));
    assertRejected(sign(validHeader().build(), validClaims().claim("exp", 1.8000009E9).build()));
  }

  @Test
  void iat나_exp가_없으면_거부한다() {
    assertRejected(sign(validHeader().build(), validClaims().expirationTime(null).build()));
    assertRejected(sign(validHeader().build(), validClaims().issueTime(null).build()));
  }

  @Test
  void 유효기간이_900초가_아니면_거부한다() {
    assertRejected(
        sign(
            validHeader().build(),
            validClaims().expirationTime(Date.from(NOW.plusSeconds(901))).build()));
    assertRejected(
        sign(
            validHeader().build(),
            validClaims().expirationTime(Date.from(NOW.plusSeconds(899))).build()));
  }

  @Test
  void exp에_도달하면_만료이며_허용_오차가_없다() {
    String token = issuedToken();

    assertThat(verifierAt(NOW.plusSeconds(899)).verify(token))
        .isInstanceOf(AccessTokenVerifier.Verification.Verified.class);
    assertThat(verifierAt(NOW.plusSeconds(900)).verify(token))
        .isInstanceOf(AccessTokenVerifier.Verification.Invalid.class);
    assertThat(verifierAt(NOW.plusSeconds(901)).verify(token))
        .isInstanceOf(AccessTokenVerifier.Verification.Invalid.class);
  }

  @Test
  void 미래_iat는_30초까지만_허용한다() {
    String token = issuedToken();

    assertThat(verifierAt(NOW.minusSeconds(30)).verify(token))
        .isInstanceOf(AccessTokenVerifier.Verification.Verified.class);
    assertThat(verifierAt(NOW.minusSeconds(31)).verify(token))
        .isInstanceOf(AccessTokenVerifier.Verification.Invalid.class);
  }

  @Test
  void iat가_exp보다_뒤면_거부한다() {
    assertRejected(
        sign(
            validHeader().build(),
            validClaims()
                .issueTime(Date.from(NOW.plusSeconds(900)))
                .expirationTime(Date.from(NOW))
                .build()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-a-uuid",
        "3F2A9C1E-1111-4111-8111-111111111111",
        "3f2a9c1e-1111-1111-8111-111111111111",
        "3f2a9c1e-1111-4111-c111-111111111111",
        "3f2a9c1e11114111811111111111111"
      })
  void jti가_canonical_UUID_v4가_아니면_거부한다(String jti) {
    assertRejected(sign(validHeader().build(), validClaims().jwtID(jti).build()));
  }

  @Test
  void jti가_없으면_거부한다() {
    assertRejected(sign(validHeader().build(), validClaims().jwtID(null).build()));
  }

  @Test
  void authVersion이_음수거나_정수가_아니면_거부한다() {
    assertRejected(sign(validHeader().build(), validClaims().claim("authVersion", -1L).build()));
    assertRejected(sign(validHeader().build(), validClaims().claim("authVersion", "0").build()));
    assertRejected(sign(validHeader().build(), validClaims().claim("authVersion", 1.5).build()));
    assertRejected(sign(validHeader().build(), validClaims().claim("authVersion", null).build()));
  }

  @Test
  void authVersion이_0_이상이면_그대로_전달한다() {
    String token = sign(validHeader().build(), validClaims().claim("authVersion", 7L).build());

    assertThat(this.verifier.verify(token))
        .isEqualTo(new AccessTokenVerifier.Verification.Verified(USER_ID, UserRole.LEARNER, 7));
  }

  @ParameterizedTest
  @ValueSource(strings = {"learner", "SUPERADMIN", "ROLE_LEARNER", ""})
  void 허용되지_않은_role을_거부한다(String role) {
    assertRejected(sign(validHeader().build(), validClaims().claim("role", role).build()));
  }

  @Test
  void role이_없으면_거부한다() {
    assertRejected(sign(validHeader().build(), validClaims().claim("role", null).build()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"LEARNER", "INSTRUCTOR", "ADMIN"})
  void 허용된_role을_그대로_전달한다(String role) {
    String token = sign(validHeader().build(), validClaims().claim("role", role).build());

    assertThat(this.verifier.verify(token))
        .isEqualTo(
            new AccessTokenVerifier.Verification.Verified(USER_ID, UserRole.valueOf(role), 0));
  }

  // ---------- 비노출 ----------

  @Test
  void 검증_결과의_toString에_사용자_정보가_없다() {
    AccessTokenVerifier.Verification verification = this.verifier.verify(issuedToken());

    assertThat(verification.toString())
        .isEqualTo("AccessTokenVerification.Verified[REDACTED]")
        .doesNotContain("42")
        .doesNotContain("LEARNER");
  }

  @Test
  void 검증_결과도_불변식을_만족해야_만들어진다() {
    assertThatThrownBy(() -> new AccessTokenVerifier.Verification.Verified(0, UserRole.LEARNER, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokenVerifier.Verification.Verified(-1, UserRole.LEARNER, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokenVerifier.Verification.Verified(1, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokenVerifier.Verification.Verified(1, UserRole.LEARNER, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 검증_결과_불변식_예외_message에_값이_없다() {
    assertThatThrownBy(() -> new AccessTokenVerifier.Verification.Verified(0, UserRole.ADMIN, 99))
        .hasMessageNotContaining("99")
        .hasMessageNotContaining("ADMIN");
  }
}
