package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.support.security.AccessTokenTestKeys;
import com.itda.user.application.port.out.AccessTokenClaims;
import com.itda.user.application.port.out.AccessTokenVerification;
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

class NimbusAccessTokenCodecTest {

  private static final String KID = "active-1";
  private static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L);
  private static final long USER_ID = 42L;

  private AccessKeyRing keyRing;
  private NimbusAccessTokenCodec codec;

  @BeforeEach
  void setUp() {
    this.keyRing = AccessKeyRing.from(AccessTokenTestKeys.properties(KID));
    this.codec = codecAt(NOW);
  }

  private NimbusAccessTokenCodec codecAt(Instant instant) {
    return new NimbusAccessTokenCodec(
        this.keyRing, AccessTokenTestKeys.ISSUER, Clock.fixed(instant, ZoneOffset.UTC));
  }

  private String issuedToken() {
    return this.codec.issue(new AccessTokenClaims(USER_ID, UserRole.LEARNER, 0));
  }

  private JWSHeader.Builder validHeader() {
    return new JWSHeader.Builder(JWSAlgorithm.HS256)
        .type(new JOSEObjectType(NimbusAccessTokenCodec.TOKEN_TYPE))
        .keyID(KID);
  }

  private JWTClaimsSet.Builder validClaims() {
    return new JWTClaimsSet.Builder()
        .issuer(AccessTokenTestKeys.ISSUER)
        .audience(NimbusAccessTokenCodec.AUDIENCE)
        .subject(Long.toString(USER_ID))
        .issueTime(Date.from(NOW))
        .expirationTime(Date.from(NOW.plusSeconds(NimbusAccessTokenCodec.TOKEN_TTL.toSeconds())))
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
    assertThat(this.codec.verify(token)).isInstanceOf(AccessTokenVerification.Invalid.class);
  }

  // ---------- 발급 ----------

  @Test
  void 발급한_토큰을_같은_설정으로_검증하면_통과한다() {
    AccessTokenVerification verification = this.codec.verify(issuedToken());

    assertThat(verification)
        .isEqualTo(new AccessTokenVerification.Verified(USER_ID, UserRole.LEARNER, 0));
  }

  @Test
  void 발급한_토큰의_JOSE_Header가_계약과_같다() throws ParseException {
    JWSHeader header = SignedJWT.parse(issuedToken()).getHeader();

    assertThat(header.getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
    assertThat(header.getType().getType()).isEqualTo("itda-access+jwt");
    assertThat(header.getKeyID()).isEqualTo(KID);
    assertThat(header.getJWKURL()).isNull();
    assertThat(header.getX509CertURL()).isNull();
  }

  @Test
  void 발급한_토큰의_payload가_계약과_같다() throws ParseException {
    JWTClaimsSet claims = SignedJWT.parse(issuedToken()).getJWTClaimsSet();

    assertThat(claims.getIssuer()).isEqualTo("itda-backend-test");
    assertThat(claims.getAudience()).containsExactly("itda-api");
    assertThat(claims.getSubject()).isEqualTo("42");
    assertThat(claims.getIssueTime()).isEqualTo(Date.from(NOW));
    assertThat(claims.getExpirationTime()).isEqualTo(Date.from(NOW.plusSeconds(900)));
    assertThat(claims.getClaim("authVersion")).isEqualTo(0L);
    assertThat(claims.getClaim("role")).isEqualTo("LEARNER");
    assertThat(UUID.fromString(claims.getJWTID()).version()).isEqualTo(4);
  }

  @Test
  void 유효기간은_정확히_900초다() throws ParseException {
    JWTClaimsSet claims = SignedJWT.parse(issuedToken()).getJWTClaimsSet();

    long seconds = (claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()) / 1000L;
    assertThat(seconds).isEqualTo(900);
  }

  @Test
  void 개인정보_claim과_nbf를_발급하지_않는다() throws ParseException {
    JWTClaimsSet claims = SignedJWT.parse(issuedToken()).getJWTClaimsSet();

    assertThat(claims.getClaim("username")).isNull();
    assertThat(claims.getClaim("email")).isNull();
    assertThat(claims.getNotBeforeTime()).isNull();
  }

  @Test
  void 같은_입력이라도_jti가_매번_다르다() throws ParseException {
    String first = SignedJWT.parse(issuedToken()).getJWTClaimsSet().getJWTID();
    String second = SignedJWT.parse(issuedToken()).getJWTClaimsSet().getJWTID();

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void 발급_입력을_검증한다() {
    assertThatThrownBy(() -> new AccessTokenClaims(0, UserRole.LEARNER, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokenClaims(-1, UserRole.LEARNER, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokenClaims(1, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokenClaims(1, UserRole.LEARNER, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- 길이·형식 ----------

  @Test
  void 상한을_넘는_토큰은_파싱하지_않고_거부한다() {
    assertRejected("a".repeat(NimbusAccessTokenCodec.MAX_TOKEN_LENGTH + 1));
    assertThat(issuedToken().length()).isLessThanOrEqualTo(NimbusAccessTokenCodec.MAX_TOKEN_LENGTH);
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
    NimbusAccessTokenCodec otherCodec =
        new NimbusAccessTokenCodec(
            otherRing, AccessTokenTestKeys.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));

    assertRejected(otherCodec.issue(new AccessTokenClaims(USER_ID, UserRole.LEARNER, 0)));
  }

  @Test
  void retired_key로_서명된_토큰도_검증한다() {
    AccessJwtProperties retiredActive = AccessTokenTestKeys.properties("retired-1");
    AccessKeyRing oldRing = AccessKeyRing.from(retiredActive);
    String oldToken =
        new NimbusAccessTokenCodec(
                oldRing, AccessTokenTestKeys.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC))
            .issue(new AccessTokenClaims(USER_ID, UserRole.ADMIN, 3));

    AccessKeyRing rotated =
        AccessKeyRing.from(
            new AccessJwtProperties(
                AccessTokenTestKeys.ISSUER,
                "active-2",
                AccessTokenTestKeys.randomKey(),
                "retired-1:" + retiredActive.activeKey()));
    NimbusAccessTokenCodec rotatedCodec =
        new NimbusAccessTokenCodec(
            rotated, AccessTokenTestKeys.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(rotatedCodec.verify(oldToken))
        .isEqualTo(new AccessTokenVerification.Verified(USER_ID, UserRole.ADMIN, 3));
  }

  // ---------- JOSE Header ----------

  @Test
  void HS256이_아닌_alg를_거부한다() {
    AccessKeyRing longKeyRing =
        AccessKeyRing.from(
            new AccessJwtProperties(
                AccessTokenTestKeys.ISSUER, KID, AccessTokenTestKeys.randomKey(64), ""));
    NimbusAccessTokenCodec longKeyCodec =
        new NimbusAccessTokenCodec(
            longKeyRing, AccessTokenTestKeys.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));
    String hs512Token;
    try {
      SignedJWT signedJwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.HS512)
                  .type(new JOSEObjectType(NimbusAccessTokenCodec.TOKEN_TYPE))
                  .keyID(KID)
                  .build(),
              validClaims().build());
      signedJwt.sign(new MACSigner(longKeyRing.activeKey()));
      hs512Token = signedJwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }

    assertThat(longKeyCodec.verify(hs512Token)).isInstanceOf(AccessTokenVerification.Invalid.class);
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
                .type(new JOSEObjectType(NimbusAccessTokenCodec.TOKEN_TYPE))
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

    assertThat(codecAt(NOW.plusSeconds(899)).verify(token))
        .isInstanceOf(AccessTokenVerification.Verified.class);
    assertThat(codecAt(NOW.plusSeconds(900)).verify(token))
        .isInstanceOf(AccessTokenVerification.Invalid.class);
    assertThat(codecAt(NOW.plusSeconds(901)).verify(token))
        .isInstanceOf(AccessTokenVerification.Invalid.class);
  }

  @Test
  void 미래_iat는_30초까지만_허용한다() {
    String token = issuedToken();

    assertThat(codecAt(NOW.minusSeconds(30)).verify(token))
        .isInstanceOf(AccessTokenVerification.Verified.class);
    assertThat(codecAt(NOW.minusSeconds(31)).verify(token))
        .isInstanceOf(AccessTokenVerification.Invalid.class);
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

    assertThat(this.codec.verify(token))
        .isEqualTo(new AccessTokenVerification.Verified(USER_ID, UserRole.LEARNER, 7));
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

    assertThat(this.codec.verify(token))
        .isEqualTo(new AccessTokenVerification.Verified(USER_ID, UserRole.valueOf(role), 0));
  }

  // ---------- 비노출 ----------

  @Test
  void 검증_결과의_toString에_사용자_정보가_없다() {
    AccessTokenVerification verification = this.codec.verify(issuedToken());

    assertThat(verification.toString())
        .isEqualTo("AccessTokenVerification.Verified[REDACTED]")
        .doesNotContain("42")
        .doesNotContain("LEARNER");
  }

  @Test
  void 검증_결과도_불변식을_만족해야_만들어진다() {
    assertThatThrownBy(() -> new AccessTokenVerification.Verified(0, UserRole.LEARNER, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokenVerification.Verified(-1, UserRole.LEARNER, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokenVerification.Verified(1, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokenVerification.Verified(1, UserRole.LEARNER, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 검증_결과_불변식_예외_message에_값이_없다() {
    assertThatThrownBy(() -> new AccessTokenVerification.Verified(0, UserRole.ADMIN, 99))
        .hasMessageNotContaining("99")
        .hasMessageNotContaining("ADMIN");
  }

  @Test
  void 발급_입력의_toString에_사용자_정보가_없다() {
    assertThat(new AccessTokenClaims(USER_ID, UserRole.ADMIN, 9).toString())
        .isEqualTo("AccessTokenClaims[REDACTED]")
        .doesNotContain("42")
        .doesNotContain("ADMIN");
  }
}
