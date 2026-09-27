package com.itda.global.security;

import com.itda.user.domain.UserRole;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;

/**
 * Access JWT 검증기 (API 명세 3.6.1절).
 *
 * <p>검증은 계약이 정한 순서를 그대로 따른다. ① 기대 종류·ring·{@code typ}·{@code aud}는 이 클래스가 Access로 이미 고정한다 ② JOSE
 * Header를 미검증 값으로 제한적으로만 읽어 ③ {@code alg}·{@code typ}·{@code kid}·{@code jku}/{@code x5u}를 확인하고 ④
 * Access ring 안에서만 {@code kid}로 key를 찾은 뒤 ⑤ 토큰이 주장하는 {@code alg}가 아니라 서버가 고정한 HS256으로 서명을 검증하고 ⑥
 * 그다음에야 payload claim을 본다.
 *
 * <p>미검증 헤더 값을 검증기나 ring을 고르는 데 쓰지 않는다 — 거부 판단에만 쓴다. 그래서 {@code typ}이 Refresh·action 값이면 ring을 바꾸는
 * 대신 그냥 거부한다.
 *
 * <p>payload는 {@link JWTClaimsSet}이 아니라 원본 JSON Map으로 읽는다. {@code exp}·{@code iat}·{@code
 * authVersion}이 정수인지 소수·문자열인지까지 구분해야 하는데, 변환된 표현에서는 그 차이가 이미 사라지기 때문이다.
 *
 * <p>실패 사유는 어디에도 남기지 않는다. 만료와 서명 불일치가 구분되면 그 자체가 공격자에게 주는 신호다.
 */
public class NimbusAccessTokenVerifier implements AccessTokenVerifier {

  /** API 명세 3.6.1절: 미래 {@code iat}는 최대 30초까지 허용한다. {@code exp} 허용 오차는 0초다. */
  static final long MAX_FUTURE_IAT_SECONDS = 30;

  /**
   * compact 토큰 길이 상한. 정상 Access JWT는 약 430자이므로 두 배 이상의 여유다. 파싱·Base64URL 디코딩 이전에 잘라내 과대 입력으로 CPU를
   * 쓰게 하는 값싼 공격을 막는다.
   */
  static final int MAX_TOKEN_LENGTH = 1024;

  /** {@code sub}: 양수 BIGINT의 정규 10진수 문자열. 선행 0과 부호를 허용하지 않는다. */
  private static final Pattern SUBJECT_PATTERN = Pattern.compile("^[1-9][0-9]{0,18}$");

  /** {@code jti}: canonical UUID v4. 13번째 자리의 4와 17번째 자리의 RFC variant까지 확인한다. */
  private static final Pattern UUID_V4_PATTERN =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  private final AccessKeyRing keyRing;
  private final String issuer;
  private final Clock clock;

  public NimbusAccessTokenVerifier(AccessKeyRing keyRing, String issuer, Clock clock) {
    this.keyRing = keyRing;
    this.issuer = issuer;
    this.clock = clock;
  }

  @Override
  public AccessTokenVerifier.Verification verify(String compactToken) {
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
    return verifyClaims(signedJwt);
  }

  /** 3단계와 4단계. 헤더 값은 거부 판단에만 쓰고 검증기 선택에는 쓰지 않는다. */
  private Optional<SecretKey> verifyHeaderAndSelectKey(JWSHeader header) {
    if (!AccessJwtContract.ALGORITHM.equals(header.getAlgorithm())) {
      return Optional.empty();
    }
    if (header.getType() == null
        || !AccessJwtContract.TOKEN_TYPE.equals(header.getType().getType())) {
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

  /** 6단계. 필수 claim이 하나라도 없거나 형식이 어긋나면 거부한다. */
  private AccessTokenVerifier.Verification verifyClaims(SignedJWT signedJwt) {
    Map<String, Object> payload = signedJwt.getPayload().toJSONObject();
    if (payload == null) {
      return invalid();
    }
    if (!this.issuer.equals(stringClaim(payload, "iss").orElse(null))) {
      return invalid();
    }
    if (!hasExactAudience(payload.get("aud"))) {
      return invalid();
    }
    Optional<Long> userId = subjectClaim(payload);
    Optional<Long> issuedAt = integerClaim(payload, "iat");
    Optional<Long> expiresAt = integerClaim(payload, "exp");
    Optional<Long> authVersion = integerClaim(payload, "authVersion");
    Optional<UserRole> role = roleClaim(payload);
    if (userId.isEmpty()
        || issuedAt.isEmpty()
        || expiresAt.isEmpty()
        || authVersion.isEmpty()
        || role.isEmpty()
        || !hasCanonicalUuidV4(payload)) {
      return invalid();
    }
    if (authVersion.get() < 0 || !hasValidTimes(issuedAt.get(), expiresAt.get())) {
      return invalid();
    }
    return new AccessTokenVerifier.Verification.Verified(
        userId.get(), role.get(), authVersion.get());
  }

  /**
   * {@code exp - iat}가 정확히 계약 유효기간이어야 하고, {@code now >= exp}는 만료이며 허용 오차는 0초다. {@code iat}는 현재보다 최대
   * 30초 미래까지만 받는다. {@code nbf}는 발급하지도, 판정에 쓰지도 않는다.
   */
  private boolean hasValidTimes(long issuedAt, long expiresAt) {
    long now = this.clock.instant().getEpochSecond();
    return issuedAt <= expiresAt
        && expiresAt - issuedAt == AccessJwtContract.TOKEN_TTL.toSeconds()
        && now < expiresAt
        && issuedAt <= now + MAX_FUTURE_IAT_SECONDS;
  }

  /** 단일 audience만 허용한다. 추가 audience가 섞인 배열은 다른 대상에도 쓰이는 토큰이므로 거부한다. */
  private static boolean hasExactAudience(Object audience) {
    if (audience instanceof String single) {
      return AccessJwtContract.AUDIENCE.equals(single);
    }
    if (audience instanceof List<?> values) {
      return values.size() == 1 && AccessJwtContract.AUDIENCE.equals(values.get(0));
    }
    return false;
  }

  private static Optional<Long> subjectClaim(Map<String, Object> payload) {
    return stringClaim(payload, "sub")
        .filter(value -> SUBJECT_PATTERN.matcher(value).matches())
        .flatMap(NimbusAccessTokenVerifier::parsePositiveLong);
  }

  private static Optional<Long> parsePositiveLong(String value) {
    try {
      return Optional.of(Long.parseLong(value));
    } catch (NumberFormatException e) {
      // 19자리 정규 10진수라도 BIGINT 범위를 넘을 수 있다.
      return Optional.empty();
    }
  }

  private static boolean hasCanonicalUuidV4(Map<String, Object> payload) {
    return stringClaim(payload, "jti")
        .filter(value -> UUID_V4_PATTERN.matcher(value).matches())
        .isPresent();
  }

  private static Optional<UserRole> roleClaim(Map<String, Object> payload) {
    return stringClaim(payload, "role")
        .flatMap(
            value -> {
              try {
                return Optional.of(UserRole.valueOf(value));
              } catch (IllegalArgumentException e) {
                return Optional.empty();
              }
            });
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

  private static AccessTokenVerifier.Verification invalid() {
    return new AccessTokenVerifier.Verification.Invalid();
  }
}
