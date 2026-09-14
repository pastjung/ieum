package com.itda.user.application;

/**
 * 로그아웃 입력 (API 명세 3.3.1절).
 *
 * <p>폐기 대상 계열은 제출된 Refresh Token이 지목한다. 사용자 식별자를 따로 받지 않는 이유는 요청이 주장하는 값을 신뢰하지 않기 위해서다 — 대상은 검증된
 * 토큰의 claim에서만 나온다.
 *
 * <p>{@code toString()}을 가린다 — Refresh Token 원문이 들어 있다.
 */
public record AuthSessionRevocationCommand(String refreshToken) {

  @Override
  public String toString() {
    return "AuthSessionRevocationCommand[REDACTED]";
  }
}
