package com.itda.global.security;

import com.itda.user.domain.UserRole;

/**
 * 인증된 요청의 principal. Access Token 검증과 저장소 인증 상태 확인을 모두 통과한 사용자다.
 *
 * <p>토큰 원문과 claim 전체를 담지 않는다. principal은 요청 처리 내내 살아 있고 예외·디버거·로그에 함께 찍히기 쉬운 객체라, 여기에 토큰을 넣으면 노출
 * 경로가 그만큼 늘어난다. 필요한 것은 "누가, 어떤 역할로" 두 가지뿐이다.
 *
 * <p>{@code toString()}도 값을 가린다 — 사용자 식별자는 로그에 남기지 않는 개인정보다.
 */
public record AuthenticatedUser(long userId, UserRole role) {

  public AuthenticatedUser {
    if (userId <= 0) {
      throw new IllegalArgumentException("사용자 식별자가 올바르지 않습니다.");
    }
    if (role == null) {
      throw new IllegalArgumentException("역할이 없습니다.");
    }
  }

  @Override
  public String toString() {
    return "AuthenticatedUser[REDACTED]";
  }
}
