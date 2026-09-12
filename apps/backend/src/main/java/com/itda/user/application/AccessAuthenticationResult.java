package com.itda.user.application;

import com.itda.user.domain.UserRole;

/**
 * Access Token 기반 인증 판정의 닫힌 결과 (API 명세 3.5·3.6.2절).
 *
 * <p>{@code Rejected}와 {@code Unavailable}을 반드시 나눈다. 저장소가 정상 응답해 얻은 결정적 결과는 {@code 401
 * UNAUTHORIZED}이고, 상태를 읽지 못했거나 결과가 불확실한 경우는 {@code 503 AUTH_STATE_UNAVAILABLE}이다. 둘을 하나로 접으면 장애를 자격
 * 증명 실패로 위장하거나 그 반대가 된다.
 *
 * <p>{@code Rejected}는 사용자 없음·비활성·이메일 미인증·세대 불일치를 구분하지 않는다. 외부에서 구분되지 않아야 하는 값을 타입으로 남기지 않는다.
 */
public sealed interface AccessAuthenticationResult {

  /** 토큰과 저장소 인증 상태를 모두 통과했다. */
  record Authenticated(long userId, UserRole role) implements AccessAuthenticationResult {

    @Override
    public String toString() {
      return "AccessAuthenticationResult.Authenticated[REDACTED]";
    }
  }

  /** 결정적으로 인증할 수 없다. */
  record Rejected() implements AccessAuthenticationResult {}

  /** 인증 상태를 확인하지 못했다. 인증 실패로 단정하지 않는다. */
  record Unavailable() implements AccessAuthenticationResult {}
}
