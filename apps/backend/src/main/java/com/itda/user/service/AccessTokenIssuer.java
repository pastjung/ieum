package com.itda.user.service;

import com.itda.user.domain.UserRole;

/**
 * Access Token 발급 outbound port. 구현은 {@link NimbusAccessTokenIssuer}다.
 *
 * <p>Access 전용이다. Refresh·action token은 key ring·{@code typ}·{@code aud}·필수 claim이 모두 다르므로 같은 port에
 * 종류를 파라미터로 넘기지 않는다 (API 명세 3.6.1절 「종류 분리」).
 */
public interface AccessTokenIssuer {

  /**
   * compact JWS 문자열과 발급기가 실제로 쓴 시각을 함께 만든다. {@code iat}·{@code exp}·{@code jti}는 서버가 생성한다.
   *
   * @return 토큰 원문을 담는다. 로그·예외 message·응답 헤더에 그대로 싣지 않는다. 만료 시각을 호출자가 다시 계산하거나 JWT를 재파싱해 복구하지 않도록 발급
   *     결과에 함께 싣는다
   */
  IssuedToken issue(Claims claims);

  /**
   * Access Token 발급 입력 (API 명세 3.6.1절 Payload claim).
   *
   * <p>{@code iat}·{@code exp}·{@code jti}는 여기에 담지 않는다 — 서버가 발급 시점에 직접 만든다. 호출자가 시각이나 식별자를 정하게 하면
   * 만료를 늘리거나 같은 {@code jti}를 재사용할 여지가 생긴다.
   *
   * <p>{@code toString()}을 재정의하는 이유: 기본 record 표현은 {@code userId}와 {@code authVersion}을 그대로 찍는다. 인증
   * 실패 로그에 사용자 식별자와 세대 값이 섞여 나가지 않게 막는다. 출력 문자열은 통합 전 top-level 타입 이름을 유지한다.
   */
  record Claims(long userId, UserRole role, long authVersion) {

    public Claims {
      if (userId <= 0) {
        throw new IllegalArgumentException("사용자 식별자가 올바르지 않습니다.");
      }
      if (role == null) {
        throw new IllegalArgumentException("역할이 없습니다.");
      }
      if (authVersion < 0) {
        throw new IllegalArgumentException("인증 세대 값이 올바르지 않습니다.");
      }
    }

    @Override
    public String toString() {
      return "AccessTokenClaims[REDACTED]";
    }
  }

  /**
   * Access Token 발급 결과 (API 명세 3.3.1절).
   *
   * <p>compact 문자열만 돌려주면 응답의 {@code accessTokenExpiresAt}을 만들 수 없다. 호출자가 "발급 시각 + TTL"을 스스로 계산하면
   * 발급기의 clock·TTL과 미세하게 어긋난 값이 나가고, 발급된 JWT를 다시 파싱해 복구하면 서명 검증 없이 payload를 읽는 경로가 생긴다. 그래서 발급기가
   * 실제로 쓴 값을 그대로 실어 보낸다.
   *
   * <p>{@code toString()}을 가린다 — compact 원문이 들어 있다. 원문은 응답으로 나가는 경로에만 존재해야 한다. 출력 문자열은 통합 전
   * top-level 타입 이름을 유지한다.
   */
  record IssuedToken(String compactToken, long issuedAtEpochSecond, long expiresAtEpochSecond) {

    public IssuedToken {
      if (compactToken == null || compactToken.isBlank()) {
        throw new IllegalArgumentException("compact 토큰이 없습니다.");
      }
      if (issuedAtEpochSecond <= 0 || expiresAtEpochSecond <= issuedAtEpochSecond) {
        throw new IllegalArgumentException("발급·만료 시각이 올바르지 않습니다.");
      }
    }

    @Override
    public String toString() {
      return "IssuedAccessToken[REDACTED]";
    }
  }
}
