package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.support.security.AccessTokenTestKeys;
import com.itda.support.security.RefreshTokenTestKeys;
import com.itda.user.application.port.out.AccessTokenClaims;
import com.itda.user.application.port.out.AccessTokenVerification;
import com.itda.user.application.port.out.IssuedRefreshToken;
import com.itda.user.application.port.out.RefreshTokenClaims;
import com.itda.user.application.port.out.RefreshTokenVerification;
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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Refresh 검증은 Access와 같은 순서를 따르지만 다른 코드 경로다. 두 종류를 바꿔 넣는 조합이 구조적으로 실패하는지까지 확인한다. */
class NimbusRefreshTokenCodecTest {

  private static final String KID = "refresh-active-1";
  private static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L);
  private static final long USER_ID = 42L;
  private static final long TTL_SECONDS = 1_209_600L;

  /** compact 토큰 자리를 채우는 값. 형식 검증 대상이 아니라 실제 토큰을 만들 필요가 없다. */
  private static final String SAMPLE_COMPACT_VALUE = "header.payload.signature";

  private String familyId;
  private RefreshKeyRing keyRing;
  private NimbusRefreshTokenCodec codec;

  @BeforeEach
  void setUp() {
    this.familyId = UUID.randomUUID().toString();
    this.keyRing = RefreshKeyRing.from(RefreshTokenTestKeys.properties(KID));
    this.codec = codecAt(NOW);
  }

  private NimbusRefreshTokenCodec codecAt(Instant instant) {
    return new NimbusRefreshTokenCodec(
        this.keyRing, AccessTokenTestKeys.ISSUER, Clock.fixed(instant, ZoneOffset.UTC));
  }

  private IssuedRefreshToken issued() {
    return this.codec.issue(new RefreshTokenClaims(USER_ID, this.familyId, 0));
  }

  private JWSHeader.Builder validHeader() {
    return new JWSHeader.Builder(JWSAlgorithm.HS256)
        .type(new JOSEObjectType(NimbusRefreshTokenCodec.TOKEN_TYPE))
        .keyID(KID);
  }

  private JWTClaimsSet.Builder validClaims() {
    return new JWTClaimsSet.Builder()
        .issuer(AccessTokenTestKeys.ISSUER)
        .audience(NimbusRefreshTokenCodec.AUDIENCE)
        .subject(Long.toString(USER_ID))
        .issueTime(Date.from(NOW))
        .expirationTime(Date.from(NOW.plusSeconds(TTL_SECONDS)))
        .jwtID(UUID.randomUUID().toString())
        .claim("familyId", this.familyId)
        .claim("authVersion", 0L);
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
    assertThat(this.codec.verify(token)).isInstanceOf(RefreshTokenVerification.Invalid.class);
  }

  // ---------- 발급 ----------

  @Test
  void 발급한_토큰을_같은_설정으로_검증하면_통과한다() {
    IssuedRefreshToken token = issued();

    assertThat(this.codec.verify(token.compactToken()))
        .isEqualTo(
            new RefreshTokenVerification.Verified(
                USER_ID,
                this.familyId,
                token.jti(),
                token.tokenHash(),
                0,
                token.issuedAtEpochSecond(),
                token.expiresAtEpochSecond()));
  }

  @Test
  void 검증_결과의_해시는_제출한_원문에서_계산한_값이다() {
    IssuedRefreshToken token = issued();

    RefreshTokenVerification.Verified verified =
        (RefreshTokenVerification.Verified) this.codec.verify(token.compactToken());

    // 상위 계층이 원문을 들고 다니며 다시 해시하지 않도록 검증기가 실어 준다.
    assertThat(verified.tokenHash()).isEqualTo(CompactTokenHasher.hash(token.compactToken()));
    // 계약이 정한 SHA-256 Base64URL(padding 없음) 43자.
    assertThat(verified.tokenHash()).hasSize(43).matches("^[A-Za-z0-9_-]{43}$");
  }

  @Test
  void 발급_결과가_저장에_필요한_값을_그대로_준다() {
    IssuedRefreshToken token = issued();

    assertThat(token.familyId()).isEqualTo(this.familyId);
    assertThat(UUID.fromString(token.jti()).version()).isEqualTo(4);
    assertThat(token.issuedAtEpochSecond()).isEqualTo(NOW.getEpochSecond());
    assertThat(token.expiresAtEpochSecond()).isEqualTo(NOW.getEpochSecond() + TTL_SECONDS);
  }

  @Test
  void 발급한_토큰의_JOSE_Header가_계약과_같다() throws ParseException {
    JWSHeader header = SignedJWT.parse(issued().compactToken()).getHeader();

    assertThat(header.getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
    assertThat(header.getType().getType()).isEqualTo("itda-refresh+jwt");
    assertThat(header.getKeyID()).isEqualTo(KID);
    assertThat(header.getJWKURL()).isNull();
    assertThat(header.getX509CertURL()).isNull();
  }

  @Test
  void 발급한_토큰의_payload가_계약과_같다() throws ParseException {
    JWTClaimsSet claims = SignedJWT.parse(issued().compactToken()).getJWTClaimsSet();

    assertThat(claims.getIssuer()).isEqualTo("itda-backend-test");
    assertThat(claims.getAudience()).containsExactly("itda-refresh");
    assertThat(claims.getSubject()).isEqualTo("42");
    assertThat(claims.getIssueTime()).isEqualTo(Date.from(NOW));
    assertThat(claims.getExpirationTime()).isEqualTo(Date.from(NOW.plusSeconds(TTL_SECONDS)));
    assertThat(claims.getClaim("familyId")).isEqualTo(this.familyId);
    assertThat(claims.getClaim("authVersion")).isEqualTo(0L);
  }

  @Test
  void 유효기간은_정확히_14일이다() throws ParseException {
    JWTClaimsSet claims = SignedJWT.parse(issued().compactToken()).getJWTClaimsSet();

    long seconds = (claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()) / 1000L;
    assertThat(seconds).isEqualTo(TTL_SECONDS);
  }

  @Test
  void 개인정보_claim과_role과_nbf를_발급하지_않는다() throws ParseException {
    JWTClaimsSet claims = SignedJWT.parse(issued().compactToken()).getJWTClaimsSet();

    assertThat(claims.getClaim("role")).isNull();
    assertThat(claims.getClaim("username")).isNull();
    assertThat(claims.getClaim("email")).isNull();
    assertThat(claims.getNotBeforeTime()).isNull();
  }

  @Test
  void 같은_입력이라도_jti가_매번_다르다() {
    assertThat(issued().jti()).isNotEqualTo(issued().jti());
  }

  @Test
  void 계열_식별자가_UUID_v4가_아니면_발급하지_않는다() {
    assertThatThrownBy(() -> this.codec.issue(new RefreshTokenClaims(USER_ID, "not-a-uuid", 0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 발급_입력을_검증한다() {
    assertThatThrownBy(() -> new RefreshTokenClaims(0, this.familyId, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RefreshTokenClaims(1, " ", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RefreshTokenClaims(1, this.familyId, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- 길이·형식 ----------

  @Test
  void 상한을_넘는_토큰은_파싱하지_않고_거부한다() {
    assertRejected("a".repeat(NimbusRefreshTokenCodec.MAX_TOKEN_LENGTH + 1));
    assertThat(issued().compactToken().length())
        .isLessThanOrEqualTo(NimbusRefreshTokenCodec.MAX_TOKEN_LENGTH);
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
    String token = issued().compactToken();

    assertRejected(token.substring(0, token.lastIndexOf('.') + 1) + "AAAAAAAAAAAAAAAAAAAAAA");
  }

  @Test
  void 다른_key로_서명한_토큰을_거부한다() {
    NimbusRefreshTokenCodec otherCodec =
        new NimbusRefreshTokenCodec(
            RefreshKeyRing.from(RefreshTokenTestKeys.properties(KID)),
            AccessTokenTestKeys.ISSUER,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertRejected(
        otherCodec.issue(new RefreshTokenClaims(USER_ID, this.familyId, 0)).compactToken());
  }

  @Test
  void retired_key로_서명된_토큰도_검증한다() {
    RefreshJwtProperties retiredActive = RefreshTokenTestKeys.properties("refresh-retired-1");
    String oldToken =
        new NimbusRefreshTokenCodec(
                RefreshKeyRing.from(retiredActive),
                AccessTokenTestKeys.ISSUER,
                Clock.fixed(NOW, ZoneOffset.UTC))
            .issue(new RefreshTokenClaims(USER_ID, this.familyId, 3))
            .compactToken();
    NimbusRefreshTokenCodec rotatedCodec =
        new NimbusRefreshTokenCodec(
            RefreshKeyRing.from(
                new RefreshJwtProperties(
                    "refresh-active-2",
                    RefreshTokenTestKeys.randomKey(),
                    "refresh-retired-1:" + retiredActive.activeKey())),
            AccessTokenTestKeys.ISSUER,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(rotatedCodec.verify(oldToken)).isInstanceOf(RefreshTokenVerification.Verified.class);
  }

  // ---------- JOSE Header ----------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "itda-access+jwt",
        "itda-email-verification+jwt",
        "itda-password-reset+jwt",
        "JWT",
        "itda-refresh+JWT"
      })
  void 기대하지_않은_typ을_거부한다(String type) {
    assertRejected(
        sign(validHeader().type(new JOSEObjectType(type)).build(), validClaims().build()));
  }

  @Test
  void typ이나_kid가_없으면_거부한다() {
    assertRejected(
        sign(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KID).build(), validClaims().build()));
    assertRejected(
        sign(
            new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(new JOSEObjectType(NimbusRefreshTokenCodec.TOKEN_TYPE))
                .build(),
            validClaims().build()));
  }

  @Test
  void ring에_없는_kid를_거부한다() {
    assertRejected(sign(validHeader().keyID("access-active").build(), validClaims().build()));
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

  @Test
  void HS256이_아닌_alg를_거부한다() {
    RefreshKeyRing longKeyRing =
        RefreshKeyRing.from(new RefreshJwtProperties(KID, RefreshTokenTestKeys.randomKey(64), ""));
    NimbusRefreshTokenCodec longKeyCodec =
        new NimbusRefreshTokenCodec(
            longKeyRing, AccessTokenTestKeys.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));
    String hs512Token;
    try {
      SignedJWT signedJwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.HS512)
                  .type(new JOSEObjectType(NimbusRefreshTokenCodec.TOKEN_TYPE))
                  .keyID(KID)
                  .build(),
              validClaims().build());
      signedJwt.sign(new MACSigner(longKeyRing.activeKey()));
      hs512Token = signedJwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }

    assertThat(longKeyCodec.verify(hs512Token))
        .isInstanceOf(RefreshTokenVerification.Invalid.class);
  }

  // ---------- claim ----------

  @Test
  void iss가_다르면_거부한다() {
    assertRejected(sign(validHeader().build(), validClaims().issuer("itda-backend-prod").build()));
    assertRejected(sign(validHeader().build(), validClaims().issuer(null).build()));
  }

  @Test
  void aud가_itda_refresh가_아니면_거부한다() {
    assertRejected(sign(validHeader().build(), validClaims().audience("itda-api").build()));
    assertRejected(sign(validHeader().build(), validClaims().audience(List.of()).build()));
    assertRejected(
        sign(
            validHeader().build(),
            validClaims().audience(List.of("itda-refresh", "itda-api")).build()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "007", "abc", " 42", "42 ", "+42", "99999999999999999999"})
  void sub가_양수_BIGINT_정규_표현이_아니면_거부한다(String subject) {
    assertRejected(sign(validHeader().build(), validClaims().subject(subject).build()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-a-uuid",
        "3F2A9C1E-1111-4111-8111-111111111111",
        "3f2a9c1e-1111-1111-8111-111111111111",
        "3f2a9c1e-1111-4111-c111-111111111111"
      })
  void jti나_familyId가_canonical_UUID_v4가_아니면_거부한다(String value) {
    assertRejected(sign(validHeader().build(), validClaims().jwtID(value).build()));
    assertRejected(sign(validHeader().build(), validClaims().claim("familyId", value).build()));
  }

  @Test
  void familyId가_없으면_거부한다() {
    assertRejected(sign(validHeader().build(), validClaims().claim("familyId", null).build()));
    assertRejected(sign(validHeader().build(), validClaims().jwtID(null).build()));
  }

  @Test
  void iat나_exp가_정수가_아니거나_없으면_거부한다() {
    assertRejected(sign(validHeader().build(), validClaims().claim("exp", "1801209600").build()));
    assertRejected(sign(validHeader().build(), validClaims().claim("iat", 1.8E9).build()));
    assertRejected(sign(validHeader().build(), validClaims().expirationTime(null).build()));
    assertRejected(sign(validHeader().build(), validClaims().issueTime(null).build()));
  }

  @Test
  void 유효기간이_14일이_아니면_거부한다() {
    assertRejected(
        sign(
            validHeader().build(),
            validClaims().expirationTime(Date.from(NOW.plusSeconds(TTL_SECONDS + 1))).build()));
    assertRejected(
        sign(
            validHeader().build(),
            validClaims().expirationTime(Date.from(NOW.plusSeconds(TTL_SECONDS - 1))).build()));
  }

  @Test
  void exp에_도달하면_만료이며_허용_오차가_없다() {
    String token = issued().compactToken();

    assertThat(codecAt(NOW.plusSeconds(TTL_SECONDS - 1)).verify(token))
        .isInstanceOf(RefreshTokenVerification.Verified.class);
    assertThat(codecAt(NOW.plusSeconds(TTL_SECONDS)).verify(token))
        .isInstanceOf(RefreshTokenVerification.Invalid.class);
  }

  @Test
  void 미래_iat는_30초까지만_허용한다() {
    String token = issued().compactToken();

    assertThat(codecAt(NOW.minusSeconds(30)).verify(token))
        .isInstanceOf(RefreshTokenVerification.Verified.class);
    assertThat(codecAt(NOW.minusSeconds(31)).verify(token))
        .isInstanceOf(RefreshTokenVerification.Invalid.class);
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

    RefreshTokenVerification verification = this.codec.verify(token);

    assertThat(verification).isInstanceOf(RefreshTokenVerification.Verified.class);
    assertThat(((RefreshTokenVerification.Verified) verification).authVersion()).isEqualTo(7);
  }

  // ---------- 비노출 ----------

  @Test
  void 검증_결과의_toString에_사용자_정보가_없다() {
    IssuedRefreshToken token = issued();

    RefreshTokenVerification verification = this.codec.verify(token.compactToken());

    assertThat(verification.toString())
        .isEqualTo("RefreshTokenVerification.Verified[REDACTED]")
        .doesNotContain("42")
        .doesNotContain(this.familyId)
        .doesNotContain(token.jti())
        .doesNotContain(token.tokenHash())
        .doesNotContain(token.compactToken());
  }

  @Test
  void 발급_결과의_toString과_equals가_토큰_원문을_드러내지_않는다() {
    IssuedRefreshToken token = issued();

    assertThat(token.toString())
        .isEqualTo("IssuedRefreshToken[REDACTED]")
        .doesNotContain(token.compactToken());
    // 같은 jti면 같은 발급이다. 원문 비교가 실패 message로 새지 않게 한다.
    assertThat(token)
        .isEqualTo(
            new IssuedRefreshToken(
                "other.compact.value",
                token.tokenHash(),
                token.jti(),
                token.familyId(),
                token.issuedAtEpochSecond(),
                token.expiresAtEpochSecond()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-a-uuid",
        "3F2A9C1E-1111-4111-8111-111111111111",
        "3f2a9c1e-1111-1111-8111-111111111111",
        "3f2a9c1e-1111-4111-c111-111111111111",
        "3f2a9c1e11114111811111111111111",
        "../other-key"
      })
  void 공백이_아니어도_UUID_v4가_아닌_식별자는_세_타입_모두_거부한다(String value) {
    // 발급 입력·발급 결과·검증 결과가 저장 계약과 같은 규칙을 따라야, 어느 한쪽만 느슨해져
    // 그대로 Redis 키가 되는 값이 새지 않는다.
    assertThatThrownBy(() -> new RefreshTokenClaims(USER_ID, value, 0))
        .isInstanceOf(IllegalArgumentException.class);

    String uuid = UUID.randomUUID().toString();
    String hash = issued().tokenHash();
    assertThatThrownBy(() -> new IssuedRefreshToken(SAMPLE_COMPACT_VALUE, hash, value, uuid, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IssuedRefreshToken(SAMPLE_COMPACT_VALUE, hash, uuid, value, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    // 해시도 저장 계약과 같은 형식(Base64URL 43자)이어야 한다.
    assertThatThrownBy(
            () -> new IssuedRefreshToken(SAMPLE_COMPACT_VALUE, "short-hash", uuid, uuid, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () -> new RefreshTokenVerification.Verified(USER_ID, value, uuid, hash, 0, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RefreshTokenVerification.Verified(USER_ID, uuid, value, hash, 0, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 식별자_거부_message에_값이_들어가지_않는다() {
    String rejected = "3F2A9C1E-1111-4111-8111-111111111111";

    assertThatThrownBy(() -> new RefreshTokenClaims(USER_ID, rejected, 0))
        .hasMessageNotContaining(rejected);
    assertThatThrownBy(
            () ->
                new IssuedRefreshToken(
                    SAMPLE_COMPACT_VALUE, issued().tokenHash(), rejected, rejected, 1, 2))
        .hasMessageNotContaining(rejected);
    assertThatThrownBy(
            () ->
                new RefreshTokenVerification.Verified(
                    USER_ID, rejected, rejected, issued().tokenHash(), 0, 1, 2))
        .hasMessageNotContaining(rejected);
  }

  @Test
  void 발급_입력의_toString에_사용자_정보가_없다() {
    assertThat(new RefreshTokenClaims(USER_ID, this.familyId, 9).toString())
        .isEqualTo("RefreshTokenClaims[REDACTED]")
        .doesNotContain("42")
        .doesNotContain(this.familyId);
  }

  @Test
  void 검증_결과도_불변식을_만족해야_만들어진다() {
    String hash = issued().tokenHash();
    assertThatThrownBy(
            () ->
                new RefreshTokenVerification.Verified(
                    0, this.familyId, UUID.randomUUID().toString(), hash, 0, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RefreshTokenVerification.Verified(
                    1, " ", UUID.randomUUID().toString(), hash, 0, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RefreshTokenVerification.Verified(1, this.familyId, " ", hash, 0, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RefreshTokenVerification.Verified(
                    1, this.familyId, UUID.randomUUID().toString(), hash, -1, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RefreshTokenVerification.Verified(
                    1, this.familyId, UUID.randomUUID().toString(), hash, 0, 2, 2))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "short-hash", "not+base64url/value+padding==", "   "})
  void 저장_계약과_다른_해시로는_검증_결과를_만들_수_없다(String invalidHash) {
    // 검증을 통과했다고 말하는 값은 그대로 Redis 조회 인자가 된다. 형식 규칙은 RefreshTokenRecord 한 곳에서만 정의한다.
    assertThatThrownBy(
            () ->
                new RefreshTokenVerification.Verified(
                    1, this.familyId, UUID.randomUUID().toString(), invalidHash, 0, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void Refresh_토큰을_Access로_검증하면_실패한다() {
    // 계약(API 명세 3.6.1 「종류 분리」)은 두 방향 조합이 모두 실패하는 테스트를 요구한다.
    NimbusAccessTokenCodec accessCodec =
        new NimbusAccessTokenCodec(
            AccessKeyRing.from(AccessTokenTestKeys.properties("access-active")),
            AccessTokenTestKeys.ISSUER,
            Clock.fixed(NOW, ZoneOffset.UTC));

    AccessTokenVerification verification = accessCodec.verify(issued().compactToken());

    assertThat(verification).isInstanceOf(AccessTokenVerification.Invalid.class);
  }

  @Test
  void Access_토큰을_Refresh로_검증하면_실패한다() {
    // 종류를 바꿔 넣는 조합이 구조적으로 실패해야 한다.
    AccessKeyRing accessRing = AccessKeyRing.from(AccessTokenTestKeys.properties("access-active"));
    String accessToken =
        new NimbusAccessTokenCodec(
                accessRing, AccessTokenTestKeys.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC))
            .issue(new AccessTokenClaims(USER_ID, UserRole.LEARNER, 0))
            .compactToken();

    assertRejected(accessToken);
  }
}
