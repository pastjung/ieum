package com.itda.user.service;

import com.itda.user.repository.RefreshTokenStore;

/**
 * Refresh Token 검증 outbound port. 구현은 {@link NimbusRefreshTokenCodec}이다.
 *
 * <p>기대 종류·key ring·{@code typ}·{@code aud}를 이 port가 Refresh로 고정한다. 토큰에 실린 값으로 검증기나 ring을 고르지 않는다.
 */
public interface RefreshTokenVerifier {

  /**
   * @param compactToken 클라이언트가 보낸 원문. 신뢰하지 않는다
   * @return 실패 사유를 구분하지 않는 닫힌 결과
   */
  Verification verify(String compactToken);

  /**
   * Refresh Token 검증의 닫힌 결과.
   *
   * <p>실패 사유를 값으로 나누지 않는다. 만료·서명 오류·claim 불일치는 외부에서 모두 {@code 401 INVALID_REFRESH_TOKEN}으로
   * 일반화되므로(API 명세 3.5절), 사유를 타입으로 남기면 그 구분이 로그나 분기로 새어 나갈 통로가 된다.
   */
  sealed interface Verification {

    /**
     * 서명과 모든 claim 검증을 통과했다. Redis 상태 확인은 아직 남아 있다.
     *
     * @param tokenHash Redis 상태를 조회·회전할 때 쓸 값. <b>원문을 이미 들고 있는 발급·검증기가 계산해 실어 준다</b> — 상위 계층이 계산하려면
     *     compact 원문을 들고 다니며 해시 구현을 import해야 하고, 그러면 원문이 유스케이스까지 퍼진다
     */
    record Verified(
        long userId,
        String familyId,
        String jti,
        String tokenHash,
        long authVersion,
        long issuedAtEpochSecond,
        long expiresAtEpochSecond)
        implements Verification {

      /** 검증을 통과했다고 말하는 값은 그 자체로 계약을 만족해야 한다. 예외 message에는 값을 넣지 않는다. */
      public Verified {
        if (userId <= 0) {
          throw new IllegalArgumentException("검증된 사용자 식별자가 올바르지 않습니다.");
        }
        // 검증을 통과했다고 말하는 식별자·해시는 저장 계약과 같은 형식이어야 한다.
        // 규칙은 RefreshTokenStore.TokenRecord 한 곳에만 두고 여기서 재사용한다.
        RefreshTokenStore.TokenRecord.requireFamilyId(familyId);
        RefreshTokenStore.TokenRecord.requireJti(jti);
        RefreshTokenStore.TokenRecord.requireTokenHash(tokenHash);
        if (authVersion < 0) {
          throw new IllegalArgumentException("검증된 인증 세대 값이 올바르지 않습니다.");
        }
        if (issuedAtEpochSecond <= 0 || expiresAtEpochSecond <= issuedAtEpochSecond) {
          throw new IllegalArgumentException("검증된 발급·만료 시각이 올바르지 않습니다.");
        }
      }

      /** 출력 문자열은 통합 전 top-level 타입 이름을 유지한다. */
      @Override
      public String toString() {
        return "RefreshTokenVerification.Verified[REDACTED]";
      }
    }

    /** 토큰을 신뢰할 수 없다. 사유는 남기지 않는다. */
    record Invalid() implements Verification {}
  }
}
