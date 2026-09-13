package com.itda.user.application.port.out;

import com.itda.user.domain.AccountActionPurpose;

/**
 * 계정 작업 토큰 검증의 닫힌 결과.
 *
 * <p>실패 사유를 값으로 나누지 않는다. 위조·만료·용도 불일치·{@code aud} 불일치는 외부에서 모두 {@code 400 ACTION_TOKEN_INVALID}로
 * 일반화되므로(API 명세 3.4.1·3.4.2절), 사유를 타입으로 남기면 그 구분이 로그나 분기로 새어 나갈 통로가 된다.
 */
public sealed interface ActionTokenVerification {

  /** 서명과 모든 claim 검증을 통과했다. Redis 상태 확인은 아직 남아 있다. */
  record Verified(
      long userId,
      AccountActionPurpose purpose,
      String jti,
      long issuedAtEpochSecond,
      long expiresAtEpochSecond)
      implements ActionTokenVerification {

    /** 검증을 통과했다고 말하는 값은 그 자체로 계약을 만족해야 한다. 예외 message에는 값을 넣지 않는다. */
    public Verified {
      ActionTokenRecord.requireUserId(userId);
      ActionTokenRecord.requirePurpose(purpose);
      ActionTokenRecord.requireJti(jti);
      if (issuedAtEpochSecond <= 0 || expiresAtEpochSecond <= issuedAtEpochSecond) {
        throw new IllegalArgumentException("검증된 발급·만료 시각이 올바르지 않습니다.");
      }
    }

    @Override
    public String toString() {
      return "ActionTokenVerification.Verified[REDACTED]";
    }
  }

  /** 토큰을 신뢰할 수 없다. 사유는 남기지 않는다. */
  record Invalid() implements ActionTokenVerification {}
}
