package com.itda.user.service;

import com.itda.user.domain.AccountActionPurpose;
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
 * 계정 작업 토큰의 공통 JOSE 처리 (API 명세 3.6.1절).
 *
 * <p><b>이 클래스는 package-private이며 application에 직접 노출되지 않는다.</b> 외부에 보이는 것은 용도별 port를 구현한 {@link
 * NimbusEmailVerificationTokenCodec}·{@link NimbusPasswordResetTokenCodec}뿐이다. 계약은 "범용 검증기 하나에 종류를
 * 파라미터로 넘기지 않으며, 종류가 다르면 다른 코드 경로를 타게 한다"고 정했고, 그 경계는 port와 bean에서 지킨다. 두 용도가 글자만 다른 같은 JOSE 절차를 두
 * 벌로 복제하면 한쪽만 고치는 사고가 나므로 절차 자체는 여기서 공유한다.
 *
 * <p><b>기대값은 생성 시점에 고정된다.</b> {@code purpose}·{@code typ}·{@code aud}·유효기간을 생성자에서 받고, 토큰에 실린 값을 읽어
 * 무엇을 기대할지 고르지 않는다. 검증 중에 읽는 {@code typ}·{@code purpose}는 거부 판단에만 쓴다.
 *
 * <p>검증 순서는 계약 순서를 그대로 따른다. ① 기대 종류·ring·{@code typ}·{@code aud}는 호출한 codec이 고정 ② JOSE Header를 미검증
 * 값으로 제한적으로만 읽고 ③ {@code alg}·{@code typ}·{@code kid}·{@code jku}/{@code x5u} 확인 ④ action ring 안에서만
 * {@code kid} 조회 ⑤ 서버가 고정한 HS256으로 서명 검증 ⑥ 그다음 payload claim.
 */
final class NimbusActionTokenCodec {

  /** API 명세 3.6.1절: 미래 {@code iat}는 최대 30초, {@code exp} 허용 오차는 0초. */
  static final long MAX_FUTURE_IAT_SECONDS = 30;

  /**
   * compact 토큰 길이 상한. action 토큰은 Refresh보다 claim이 적어 400자 안팎이다. 두 배 이상 여유를 두되, 파싱·Base64URL 디코딩 이전에
   * 잘라내 과대 입력으로 CPU를 쓰게 하는 공격을 막는다.
   */
  static final int MAX_TOKEN_LENGTH = 1024;

  /** {@code sub}: 양수 BIGINT의 정규 10진수 문자열. 선행 0과 부호를 허용하지 않는다. */
  private static final Pattern SUBJECT_PATTERN = Pattern.compile("^[1-9][0-9]{0,18}$");

  /** {@code jti}: canonical UUID v4. 버전 자리의 4와 RFC variant까지 확인한다. */
  private static final Pattern UUID_V4_PATTERN =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  private final AccountActionPurpose purpose;
  private final JOSEObjectType tokenType;
  private final String tokenTypeValue;
  private final String audience;
  private final Duration timeToLive;
  private final ActionKeyRing keyRing;
  private final String issuer;
  private final Clock clock;

  NimbusActionTokenCodec(
      AccountActionPurpose purpose,
      String tokenTypeValue,
      String audience,
      Duration timeToLive,
      ActionKeyRing keyRing,
      String issuer,
      Clock clock) {
    this.purpose = purpose;
    this.tokenTypeValue = tokenTypeValue;
    this.tokenType = new JOSEObjectType(tokenTypeValue);
    this.audience = audience;
    this.timeToLive = timeToLive;
    this.keyRing = keyRing;
    this.issuer = issuer;
    this.clock = clock;
  }

  IssuedActionToken issue(ActionTokenClaims claims) {
    long issuedAt = this.clock.instant().getEpochSecond();
    long expiresAt = issuedAt + this.timeToLive.toSeconds();
    String jti = UUID.randomUUID().toString();
    JWSHeader header =
        new JWSHeader.Builder(JWSAlgorithm.HS256)
            .type(this.tokenType)
            .keyID(this.keyRing.activeKid())
            .build();
    JWTClaimsSet claimsSet =
        new JWTClaimsSet.Builder()
            .issuer(this.issuer)
            .audience(this.audience)
            .subject(Long.toString(claims.userId()))
            .issueTime(Date.from(Instant.ofEpochSecond(issuedAt)))
            .expirationTime(Date.from(Instant.ofEpochSecond(expiresAt)))
            .jwtID(jti)
            .claim("purpose", this.purpose.name())
            .build();
    SignedJWT signedJwt = new SignedJWT(header, claimsSet);
    try {
      signedJwt.sign(new MACSigner(this.keyRing.activeKey()));
    } catch (JOSEException e) {
      // 원인 객체를 그대로 올리면 key 정보가 따라 나갈 수 있다.
      throw new IllegalStateException("계정 작업 토큰에 서명하지 못했습니다.");
    }
    String compactToken = signedJwt.serialize();
    return new IssuedActionToken(
        compactToken,
        CompactTokenHasher.hash(compactToken),
        jti,
        this.purpose,
        issuedAt,
        expiresAt);
  }

  ActionTokenVerification verify(String compactToken) {
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

  /** 3단계와 4단계. 헤더 값은 거부 판단에만 쓰고 검증기나 ring 선택에는 쓰지 않는다. */
  private Optional<SecretKey> verifyHeaderAndSelectKey(JWSHeader header) {
    if (!JWSAlgorithm.HS256.equals(header.getAlgorithm())) {
      return Optional.empty();
    }
    if (header.getType() == null || !this.tokenTypeValue.equals(header.getType().getType())) {
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
   * <p>통과한 결과에 {@code tokenHash}를 실어 보낸다. Redis 소비에 필요한 값인데 토큰 원문에서만 만들 수 있고, 원문을 가진 곳은 여기뿐이다 —
   * 유스케이스가 원문을 들고 다니지 않게 한다.
   */
  private ActionTokenVerification verifyClaims(SignedJWT signedJwt, String compactToken) {
    Map<String, Object> payload = signedJwt.getPayload().toJSONObject();
    if (payload == null) {
      return invalid();
    }
    if (!this.issuer.equals(stringClaim(payload, "iss").orElse(null))
        || !hasExactAudience(payload.get("aud"))
        || !this.purpose.name().equals(stringClaim(payload, "purpose").orElse(null))) {
      return invalid();
    }
    Optional<Long> userId = subjectClaim(payload);
    Optional<Long> issuedAt = integerClaim(payload, "iat");
    Optional<Long> expiresAt = integerClaim(payload, "exp");
    Optional<String> jti =
        stringClaim(payload, "jti").filter(value -> UUID_V4_PATTERN.matcher(value).matches());
    if (userId.isEmpty() || issuedAt.isEmpty() || expiresAt.isEmpty() || jti.isEmpty()) {
      return invalid();
    }
    if (!hasValidTimes(issuedAt.get(), expiresAt.get())) {
      return invalid();
    }
    return new ActionTokenVerification.Verified(
        userId.get(),
        this.purpose,
        jti.get(),
        CompactTokenHasher.hash(compactToken),
        issuedAt.get(),
        expiresAt.get());
  }

  /**
   * {@code exp - iat}가 정확히 이 용도의 유효기간이어야 하고, {@code now >= exp}는 만료이며 허용 오차는 0초다. {@code iat}는 현재보다
   * 최대 30초 미래까지만 받는다. {@code nbf}는 발급하지도, 판정에 쓰지도 않는다.
   */
  private boolean hasValidTimes(long issuedAt, long expiresAt) {
    long now = this.clock.instant().getEpochSecond();
    return issuedAt <= expiresAt
        && expiresAt - issuedAt == this.timeToLive.toSeconds()
        && now < expiresAt
        && issuedAt <= now + MAX_FUTURE_IAT_SECONDS;
  }

  /** 단일 audience만 허용한다. 추가 audience가 섞인 배열은 다른 대상에도 쓰이는 토큰이므로 거부한다. */
  private boolean hasExactAudience(Object audienceClaim) {
    if (audienceClaim instanceof String single) {
      return this.audience.equals(single);
    }
    if (audienceClaim instanceof List<?> values) {
      return values.size() == 1 && this.audience.equals(values.get(0));
    }
    return false;
  }

  private static Optional<Long> subjectClaim(Map<String, Object> payload) {
    return stringClaim(payload, "sub")
        .filter(value -> SUBJECT_PATTERN.matcher(value).matches())
        .flatMap(NimbusActionTokenCodec::parsePositiveLong);
  }

  private static Optional<Long> parsePositiveLong(String value) {
    try {
      return Optional.of(Long.parseLong(value));
    } catch (NumberFormatException e) {
      // 19자리 정규 10진수라도 BIGINT 범위를 넘을 수 있다.
      return Optional.empty();
    }
  }

  private static Optional<String> stringClaim(Map<String, Object> payload, String name) {
    return payload.get(name) instanceof String value ? Optional.of(value) : Optional.empty();
  }

  /** 소수·문자열로 실린 시각을 거부하기 위해 원본 JSON의 타입을 그대로 본다. */
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

  private static ActionTokenVerification invalid() {
    return new ActionTokenVerification.Invalid();
  }
}
