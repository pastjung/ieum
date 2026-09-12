package com.itda.user.application.port.out;

import com.itda.user.domain.UserRole;

/**
 * Access Token 검증의 닫힌 결과.
 *
 * <p>실패 사유를 값으로 나누지 않는다. API 명세 3.5절이 서명 오류·만료·claim 불일치를 외부에서 구분하지 않는 하나의 {@code 401
 * UNAUTHORIZED}로 일반화하므로, 사유를 타입으로 남기면 그 구분이 로그나 분기로 새어 나갈 통로가 된다.
 */
public sealed interface AccessTokenVerification {

  /** 서명과 모든 claim 검증을 통과했다. 저장소의 인증 상태 확인은 아직 남아 있다. */
  record Verified(long userId, UserRole role, long authVersion) implements AccessTokenVerification {

    /**
     * 검증을 통과했다고 말하는 값은 그 자체로 계약을 만족해야 한다. 예외 message에는 값을 넣지 않는다 — 이 타입은 인증 실패 경로에서 만들어지고, 그
     * message가 로그나 오류 응답으로 이어질 수 있다.
     */
    public Verified {
      if (userId <= 0) {
        throw new IllegalArgumentException("검증된 사용자 식별자가 올바르지 않습니다.");
      }
      if (role == null) {
        throw new IllegalArgumentException("검증된 역할이 없습니다.");
      }
      if (authVersion < 0) {
        throw new IllegalArgumentException("검증된 인증 세대 값이 올바르지 않습니다.");
      }
    }

    @Override
    public String toString() {
      return "AccessTokenVerification.Verified[REDACTED]";
    }
  }

  /** 토큰을 신뢰할 수 없다. 사유는 남기지 않는다. */
  record Invalid() implements AccessTokenVerification {}
}
