package com.itda.user.application.port.out;

/**
 * Refresh Token 검증의 닫힌 결과.
 *
 * <p>실패 사유를 값으로 나누지 않는다. 만료·서명 오류·claim 불일치는 외부에서 모두 {@code 401 INVALID_REFRESH_TOKEN}으로 일반화되므로(API
 * 명세 3.5절), 사유를 타입으로 남기면 그 구분이 로그나 분기로 새어 나갈 통로가 된다.
 */
public sealed interface RefreshTokenVerification {

  /** 서명과 모든 claim 검증을 통과했다. Redis 상태 확인은 아직 남아 있다. */
  record Verified(
      long userId,
      String familyId,
      String jti,
      long authVersion,
      long issuedAtEpochSecond,
      long expiresAtEpochSecond)
      implements RefreshTokenVerification {

    /** 검증을 통과했다고 말하는 값은 그 자체로 계약을 만족해야 한다. 예외 message에는 값을 넣지 않는다. */
    public Verified {
      if (userId <= 0) {
        throw new IllegalArgumentException("검증된 사용자 식별자가 올바르지 않습니다.");
      }
      // 검증을 통과했다고 말하는 식별자는 저장 계약과 같은 형식이어야 한다.
      // 규칙은 RefreshTokenRecord 한 곳에만 두고 여기서 재사용한다.
      RefreshTokenRecord.requireFamilyId(familyId);
      RefreshTokenRecord.requireJti(jti);
      if (authVersion < 0) {
        throw new IllegalArgumentException("검증된 인증 세대 값이 올바르지 않습니다.");
      }
      if (issuedAtEpochSecond <= 0 || expiresAtEpochSecond <= issuedAtEpochSecond) {
        throw new IllegalArgumentException("검증된 발급·만료 시각이 올바르지 않습니다.");
      }
    }

    @Override
    public String toString() {
      return "RefreshTokenVerification.Verified[REDACTED]";
    }
  }

  /** 토큰을 신뢰할 수 없다. 사유는 남기지 않는다. */
  record Invalid() implements RefreshTokenVerification {}
}
