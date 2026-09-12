package com.itda.user.application.port.out;

import com.itda.user.domain.UserRole;

/**
 * Access Token 발급 입력 (API 명세 3.6.1절 Payload claim).
 *
 * <p>{@code iat}·{@code exp}·{@code jti}는 여기에 담지 않는다 — 서버가 발급 시점에 직접 만든다. 호출자가 시각이나 식별자를 정하게 하면 만료를
 * 늘리거나 같은 {@code jti}를 재사용할 여지가 생긴다.
 *
 * <p>{@code toString()}을 재정의하는 이유: 기본 record 표현은 {@code userId}와 {@code authVersion}을 그대로 찍는다. 인증
 * 실패 로그에 사용자 식별자와 세대 값이 섞여 나가지 않게 막는다.
 */
public record AccessTokenClaims(long userId, UserRole role, long authVersion) {

  public AccessTokenClaims {
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
