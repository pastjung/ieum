package com.itda.global.security;

import com.nimbusds.jose.JWSAlgorithm;
import java.time.Duration;

/**
 * Access JWT 계약 값 (API 명세 3.6.1절).
 *
 * <p>발급기({@code user.service}의 {@code NimbusAccessTokenIssuer})와 검증기({@link
 * NimbusAccessTokenVerifier})가 이 값만 읽는다. 한쪽에 복제하면 두 값이 어긋나는 순간 발급한 토큰을 스스로 거부하거나, 계약과 다른 토큰을 받아들이게
 * 된다.
 */
public final class AccessJwtContract {

  /** 서버가 고정한 서명 알고리즘. 토큰이 주장하는 {@code alg}로 검증기를 고르지 않는다. */
  public static final JWSAlgorithm ALGORITHM = JWSAlgorithm.HS256;

  /** API 명세 3.6.1절: Access 유효기간 15분. 설정으로 열지 않는다 — 계약 값이다. */
  public static final Duration TOKEN_TTL = Duration.ofMinutes(15);

  public static final String TOKEN_TYPE = "itda-access+jwt";
  public static final String AUDIENCE = "itda-api";

  private AccessJwtContract() {}
}
