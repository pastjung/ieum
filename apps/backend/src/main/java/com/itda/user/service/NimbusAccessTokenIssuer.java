package com.itda.user.service;

import com.itda.global.security.AccessJwtContract;
import com.itda.global.security.AccessKeyRing;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.util.Date;
import java.util.UUID;

/**
 * Access JWT 발급기 (API 명세 3.6.1절).
 *
 * <p>서명 알고리즘·{@code typ}·{@code aud}·유효기간은 {@link AccessJwtContract}에서 읽고 여기에 복제하지 않는다. 검증은 같은
 * ring·issuer를 쓰는 {@code global.security}의 {@code NimbusAccessTokenVerifier}가 맡는다.
 */
public class NimbusAccessTokenIssuer implements AccessTokenIssuer {

  private static final JOSEObjectType ACCESS_TYPE =
      new JOSEObjectType(AccessJwtContract.TOKEN_TYPE);

  private final AccessKeyRing keyRing;
  private final String issuer;
  private final Clock clock;

  public NimbusAccessTokenIssuer(AccessKeyRing keyRing, String issuer, Clock clock) {
    this.keyRing = keyRing;
    this.issuer = issuer;
    this.clock = clock;
  }

  @Override
  public AccessTokenIssuer.IssuedToken issue(AccessTokenIssuer.Claims claims) {
    long issuedAt = this.clock.instant().getEpochSecond();
    long expiresAt = issuedAt + AccessJwtContract.TOKEN_TTL.toSeconds();
    JWSHeader header =
        new JWSHeader.Builder(AccessJwtContract.ALGORITHM)
            .type(ACCESS_TYPE)
            .keyID(this.keyRing.activeKid())
            .build();
    JWTClaimsSet claimsSet =
        new JWTClaimsSet.Builder()
            .issuer(this.issuer)
            .audience(AccessJwtContract.AUDIENCE)
            .subject(Long.toString(claims.userId()))
            .issueTime(Date.from(java.time.Instant.ofEpochSecond(issuedAt)))
            .expirationTime(Date.from(java.time.Instant.ofEpochSecond(expiresAt)))
            .jwtID(UUID.randomUUID().toString())
            .claim("authVersion", claims.authVersion())
            .claim("role", claims.role().name())
            .build();
    SignedJWT signedJwt = new SignedJWT(header, claimsSet);
    try {
      signedJwt.sign(new MACSigner(this.keyRing.activeKey()));
    } catch (JOSEException e) {
      // 서명 실패는 key 설정이 무너진 상태다. 원인 객체를 그대로 올리면 key 정보가 따라 나갈 수 있다.
      throw new IllegalStateException("Access Token에 서명하지 못했습니다.");
    }
    // 발급에 실제로 쓴 시각을 함께 돌려준다. 호출자가 TTL을 다시 더하거나 JWT를 재파싱하지 않게 한다.
    return new AccessTokenIssuer.IssuedToken(signedJwt.serialize(), issuedAt, expiresAt);
  }
}
