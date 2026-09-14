package com.itda.user.infrastructure;

import com.itda.user.application.port.out.IssuedRefreshToken;
import com.itda.user.application.port.out.RefreshTokenClaims;
import com.itda.user.application.port.out.RefreshTokenIssuer;
import com.itda.user.application.port.out.RefreshTokenVerification;
import com.itda.user.application.port.out.RefreshTokenVerifier;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;

/**
 * Refresh JWT 발급·검증 어댑터 (API 명세 3.6.1절).
 *
 * <p>Access 검증기와 합치지 않는다. 계약이 "범용 검증기 하나에 종류를 파라미터로 넘기지 않으며, 종류가 다르면 다른 코드 경로를 타게 한다"고 못박았고, 그래야
 * Access를 갱신 endpoint에, Refresh를 보호 API에 넣는 조합이 구조적으로 실패한다.
 *
 * <p>검증 순서는 Access와 동일한 계약 순서를 따른다. ① 기대 종류·ring·{@code typ}·{@code aud}는 이 클래스가 Refresh로 고정 ② JOSE
 * Header를 미검증 값으로 제한적으로만 읽고 ③ {@code alg}·{@code typ}·{@code kid}·{@code jku}/{@code x5u} 확인 ④
 * Refresh ring 안에서만 {@code kid} 조회 ⑤ 서버가 고정한 HS256으로 서명 검증 ⑥ 그다음 payload claim.
 *
 * <p>payload는 원본 JSON Map으로 읽는다. {@code iat}·{@code exp}·{@code authVersion}이 정수인지 소수·문자열인지까지 구분해야
 * 하는데 변환된 표현에서는 그 차이가 사라진다.
 */
public class NimbusRefreshTokenCodec implements RefreshTokenIssuer, RefreshTokenVerifier {

  /** API 명세 3.6.1절: Refresh 유효기간 14일. 계약 값이라 설정으로 열지 않는다. */
  static final Duration TOKEN_TTL = Duration.ofDays(14);

  static final String TOKEN_TYPE = "itda-refresh+jwt";
  static final String AUDIENCE = "itda-refresh";

  /** API 명세 3.6.1절: 미래 {@code iat}는 최대 30초, {@code exp} 허용 오차는 0초. */
  static final long MAX_FUTURE_IAT_SECONDS = 30;

  /**
   * compact 토큰 길이 상한. Refresh는 Access보다 claim이 하나 많고 값이 UUID라 약 520자다. 두 배 가까운 여유를 두되, 파싱·Base64URL
   * 디코딩 이전에 잘라내 과대 입력으로 CPU를 쓰게 하는 공격을 막는다.
   */
  static final int MAX_TOKEN_LENGTH = 1024;

  /** {@code sub}: 양수 BIGINT의 정규 10진수 문자열. 선행 0과 부호를 허용하지 않는다. */
  private static final Pattern SUBJECT_PATTERN = Pattern.compile("^[1-9][0-9]{0,18}$");

  /** {@code jti}·{@code familyId}: canonical UUID v4. 버전 자리의 4와 RFC variant까지 확인한다. */
  private static final Pattern UUID_V4_PATTERN =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  private static final JOSEObjectType REFRESH_TYPE = new JOSEObjectType(TOKEN_TYPE);

  private final RefreshKeyRing keyRing;
  private final String issuer;
  private final Clock clock;

  public NimbusRefreshTokenCodec(RefreshKeyRing keyRing, String issuer, Clock clock) {
    this.keyRing = keyRing;
    this.issuer = issuer;
    this.clock = clock;
  }

  @Override
  public IssuedRefreshToken issue(RefreshTokenClaims claims) {
    if (!UUID_V4_PATTERN.matcher(claims.familyId()).matches()) {
      throw new IllegalArgumentException("계열 식별자가 UUID v4 형식이 아닙니다.");
    }
    long issuedAt = this.clock.instant().getEpochSecond();
    long expiresAt = issuedAt + TOKEN_TTL.toSeconds();
    String jti = UUID.randomUUID().toString();
    JWSHeader header =
        new JWSHeader.Builder(JWSAlgorithm.HS256)
            .type(REFRESH_TYPE)
            .keyID(this.keyRing.activeKid())
            .build();
    JWTClaimsSet claimsSet =
        new JWTClaimsSet.Builder()
            .issuer(this.issuer)
            .audience(AUDIENCE)
            .subject(Long.toString(claims.userId()))
            .issueTime(Date.from(Instant.ofEpochSecond(issuedAt)))
            .expirationTime(Date.from(Instant.ofEpochSecond(expiresAt)))
            .jwtID(jti)
            .claim("familyId", claims.familyId())
            .claim("authVersion", claims.authVersion())
            .build();
    SignedJWT signedJwt = new SignedJWT(header, claimsSet);
    try {
      signedJwt.sign(new MACSigner(this.keyRing.activeKey()));
    } catch (JOSEException e) {
      // 원인 객체를 그대로 올리면 key 정보가 따라 나갈 수 있다.
      throw new IllegalStateException("Refresh Token에 서명하지 못했습니다.");
    }
    String compactToken = signedJwt.serialize();
    return new IssuedRefreshToken(
        compactToken,
        // 해시는 원문을 가진 여기서 만든다. 상위 계층이 원문을 들고 다니지 않게 하는 경계다.
        CompactTokenHasher.hash(compactToken),
        jti,
        claims.familyId(),
        issuedAt,
        expiresAt);
  }

  @Override
  public RefreshTokenVerification verify(String compactToken) {
    if (compactToken == null
        || compactToken.isEmpty()
        || compactToken.length() > MAX_TOKEN_LENGTH) {
      return invalid();
    }
    SignedJWT signedJwt;
    try {
      signedJwt = SignedJWT.parse(compactToken);
    } catch (ParseException e) {
      return invalid();
    }
    Optional<SecretKey> key = verifyHeaderAndSelectKey(signedJwt.getHeader());
    if (key.isEmpty() || !hasValidSignature(signedJwt, key.get())) {
      return invalid();
    }
    return verifyClaims(signedJwt, compactToken);
  }

  /** 3단계와 4단계. 헤더 값은 거부 판단에만 쓰고 검증기 선택에는 쓰지 않는다. */
  private Optional<SecretKey> verifyHeaderAndSelectKey(JWSHeader header) {
    if (!JWSAlgorithm.HS256.equals(header.getAlgorithm())) {
      return Optional.empty();
    }
    if (header.getType() == null || !TOKEN_TYPE.equals(header.getType().getType())) {
      return Optional.empty();
    }
    if (header.getJWKURL() != null || header.getX509CertURL() != null) {
      // 외부 key 조회를 유발하는 헤더는 지원하지 않는다.
      return Optional.empty();
    }
    String kid = header.getKeyID();
    if (kid == null || kid.isBlank()) {
      return Optional.empty();
    }
    return this.keyRing.findByKid(kid);
  }

  /** 5단계. 토큰이 주장하는 alg가 아니라 서버가 고정한 HS256 verifier를 쓴다. */
  private static boolean hasValidSignature(SignedJWT signedJwt, SecretKey key) {
    try {
      return signedJwt.verify(new MACVerifier(key));
    } catch (JOSEException e) {
      return false;
    }
  }

  /**
   * 6단계. 필수 claim이 하나라도 없거나 형식이 어긋나면 거부한다.
   *
   * <p>모든 검증을 통과한 뒤에만 {@code tokenHash}를 만든다. 상위 계층은 Redis 상태를 조회·회전할 때 이 값이 필요한데, 원문을 들고 올라가면
   * application이 해시 구현을 알아야 하고 원문이 그만큼 멀리 퍼진다. 원문을 이미 갖고 있는 이 자리에서 계산해 결과에만 싣는다.
   */
  private RefreshTokenVerification verifyClaims(SignedJWT signedJwt, String compactToken) {
    Map<String, Object> payload = signedJwt.getPayload().toJSONObject();
    if (payload == null) {
      return invalid();
    }
    if (!this.issuer.equals(stringClaim(payload, "iss").orElse(null))
        || !hasExactAudience(payload.get("aud"))) {
      return invalid();
    }
    Optional<Long> userId = subjectClaim(payload);
    Optional<Long> issuedAt = integerClaim(payload, "iat");
    Optional<Long> expiresAt = integerClaim(payload, "exp");
    Optional<Long> authVersion = integerClaim(payload, "authVersion");
    Optional<String> jti = canonicalUuidClaim(payload, "jti");
    Optional<String> familyId = canonicalUuidClaim(payload, "familyId");
    if (userId.isEmpty()
        || issuedAt.isEmpty()
        || expiresAt.isEmpty()
        || authVersion.isEmpty()
        || jti.isEmpty()
        || familyId.isEmpty()) {
      return invalid();
    }
    if (authVersion.get() < 0 || !hasValidTimes(issuedAt.get(), expiresAt.get())) {
      return invalid();
    }
    return new RefreshTokenVerification.Verified(
        userId.get(),
        familyId.get(),
        jti.get(),
        CompactTokenHasher.hash(compactToken),
        authVersion.get(),
        issuedAt.get(),
        expiresAt.get());
  }

  /**
   * {@code exp - iat}가 정확히 계약 유효기간이어야 하고, {@code now >= exp}는 만료이며 허용 오차는 0초다. {@code iat}는 현재보다 최대
   * 30초 미래까지만 받는다. {@code nbf}는 발급하지도, 판정에 쓰지도 않는다.
   */
  private boolean hasValidTimes(long issuedAt, long expiresAt) {
    long now = this.clock.instant().getEpochSecond();
    return issuedAt <= expiresAt
        && expiresAt - issuedAt == TOKEN_TTL.toSeconds()
        && now < expiresAt
        && issuedAt <= now + MAX_FUTURE_IAT_SECONDS;
  }

  /** 단일 audience만 허용한다. 추가 audience가 섞인 배열은 다른 대상에도 쓰이는 토큰이므로 거부한다. */
  private static boolean hasExactAudience(Object audience) {
    if (audience instanceof String single) {
      return AUDIENCE.equals(single);
    }
    if (audience instanceof List<?> values) {
      return values.size() == 1 && AUDIENCE.equals(values.get(0));
    }
    return false;
  }

  private static Optional<Long> subjectClaim(Map<String, Object> payload) {
    return stringClaim(payload, "sub")
        .filter(value -> SUBJECT_PATTERN.matcher(value).matches())
        .flatMap(NimbusRefreshTokenCodec::parsePositiveLong);
  }

  private static Optional<Long> parsePositiveLong(String value) {
    try {
      return Optional.of(Long.parseLong(value));
    } catch (NumberFormatException e) {
      // 19자리 정규 10진수라도 BIGINT 범위를 넘을 수 있다.
      return Optional.empty();
    }
  }

  private static Optional<String> canonicalUuidClaim(Map<String, Object> payload, String name) {
    return stringClaim(payload, name).filter(value -> UUID_V4_PATTERN.matcher(value).matches());
  }

  private static Optional<String> stringClaim(Map<String, Object> payload, String name) {
    return payload.get(name) instanceof String value ? Optional.of(value) : Optional.empty();
  }

  /** 소수·문자열로 실린 시각과 세대 값을 거부하기 위해 원본 JSON의 타입을 그대로 본다. */
  private static Optional<Long> integerClaim(Map<String, Object> payload, String name) {
    Object value = payload.get(name);
    if (value instanceof Long longValue) {
      return Optional.of(longValue);
    }
    if (value instanceof Integer intValue) {
      return Optional.of(intValue.longValue());
    }
    return Optional.empty();
  }

  private static RefreshTokenVerification invalid() {
    return new RefreshTokenVerification.Invalid();
  }
}
