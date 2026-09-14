package com.itda.user.application;

/**
 * Access Token 갱신 입력 (API 명세 3.3.1절).
 *
 * <p>플랫폼을 담지 않는다. {@code X-Client-Platform}은 Refresh Token을 쿠키에서 읽을지 body에서 읽을지 고르는 값일 뿐이고, 그 판정은
 * presentation이 이미 끝냈다. 유스케이스에는 "제출된 Refresh Token 하나"만 들어온다.
 *
 * <p>{@code toString()}을 가린다 — Refresh Token 원문이 들어 있다.
 */
public record AccessTokenRefreshCommand(String refreshToken) {

  @Override
  public String toString() {
    return "AccessTokenRefreshCommand[REDACTED]";
  }
}
