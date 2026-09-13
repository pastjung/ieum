package com.itda.user.application;

/**
 * 로그인 입력 (API 명세 3.3.1절).
 *
 * <p>플랫폼을 담지 않는다. {@code X-Client-Platform}은 Refresh Token 전달 위치를 고르는 값일 뿐 인증 판정의 근거가 아니므로,
 * application이 그 값을 알면 판정과 전달이 섞인다.
 *
 * <p>{@code toString()}을 가린다 — 아이디와 비밀번호 원문이 들어 있다.
 */
public record LoginCommand(String username, String password) {

  @Override
  public String toString() {
    return "LoginCommand[REDACTED]";
  }
}
