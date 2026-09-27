package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.global.security.AccessKeyRing;
import com.itda.global.security.AccessTokenVerifier;
import com.itda.global.security.NimbusAccessTokenVerifier;
import com.itda.support.security.AccessTokenTestKeys;
import com.itda.user.domain.UserRole;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NimbusAccessTokenIssuerTest {

  private static final String KID = "active-1";
  private static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L);
  private static final long USER_ID = 42L;

  private AccessKeyRing keyRing;
  private NimbusAccessTokenIssuer issuer;

  @BeforeEach
  void setUp() {
    this.keyRing = AccessKeyRing.from(AccessTokenTestKeys.properties(KID));
    this.issuer =
        new NimbusAccessTokenIssuer(
            this.keyRing, AccessTokenTestKeys.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private String issuedToken() {
    return this.issuer
        .issue(new AccessTokenIssuer.Claims(USER_ID, UserRole.LEARNER, 0))
        .compactToken();
  }

  // ---------- 발급 ----------

  @Test
  void 발급한_토큰을_같은_설정으로_검증하면_통과한다() {
    NimbusAccessTokenVerifier verifier =
        new NimbusAccessTokenVerifier(
            this.keyRing, AccessTokenTestKeys.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));

    AccessTokenVerifier.Verification verification = verifier.verify(issuedToken());

    assertThat(verification)
        .isEqualTo(new AccessTokenVerifier.Verification.Verified(USER_ID, UserRole.LEARNER, 0));
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
    assertThatThrownBy(() -> new AccessTokenIssuer.Claims(0, UserRole.LEARNER, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokenIssuer.Claims(-1, UserRole.LEARNER, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokenIssuer.Claims(1, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokenIssuer.Claims(1, UserRole.LEARNER, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- 비노출 ----------

  @Test
  void 발급_입력의_toString에_사용자_정보가_없다() {
    assertThat(new AccessTokenIssuer.Claims(USER_ID, UserRole.ADMIN, 9).toString())
        .isEqualTo("AccessTokenClaims[REDACTED]")
        .doesNotContain("42")
        .doesNotContain("ADMIN");
  }
}
