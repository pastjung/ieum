package com.itda.global.security;

import com.itda.user.domain.UserRole;

/**
 * Access Token 검증 outbound port. 구현은 {@link NimbusAccessTokenVerifier}다.
 *
 * <p>기대하는 토큰 종류·key ring·{@code typ}·{@code aud}는 이 port가 Access로 이미 고정한다. 토큰에 실린 값을 보고 검증기나 ring을
 * 고르지 않는다 (API 명세 3.6.1절 「검증 순서」).
 */
public interface AccessTokenVerifier {

  /**
   * @param compactToken Authorization 헤더에서 꺼낸 원문. 신뢰하지 않는다
   * @return 실패 사유를 구분하지 않는 닫힌 결과
   */
  Verification verify(String compactToken);

  /**
   * Access Token 검증의 닫힌 결과.
   *
   * <p>실패 사유를 값으로 나누지 않는다. API 명세 3.5절이 서명 오류·만료·claim 불일치를 외부에서 구분하지 않는 하나의 {@code 401
   * UNAUTHORIZED}로 일반화하므로, 사유를 타입으로 남기면 그 구분이 로그나 분기로 새어 나갈 통로가 된다.
   */
  sealed interface Verification {

    /** 서명과 모든 claim 검증을 통과했다. 저장소의 인증 상태 확인은 아직 남아 있다. */
    record Verified(long userId, UserRole role, long authVersion) implements Verification {

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

      /** 출력 문자열은 통합 전 top-level 타입 이름을 유지한다. */
      @Override
      public String toString() {
        return "AccessTokenVerification.Verified[REDACTED]";
      }
    }

    /** 토큰을 신뢰할 수 없다. 사유는 남기지 않는다. */
    record Invalid() implements Verification {}
  }
}
