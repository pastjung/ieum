package com.itda.user.application;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.application.port.out.RefreshFamilyRevocationResult;
import com.itda.user.application.port.out.RefreshTokenLookupResult;
import com.itda.user.application.port.out.RefreshTokenRecord;
import com.itda.user.application.port.out.RefreshTokenStore;
import com.itda.user.application.port.out.RefreshTokenVerification;
import com.itda.user.application.port.out.RefreshTokenVerifier;

/**
 * 로그아웃 — 제출된 Refresh Token의 현재 계열을 폐기한다 (API 명세 3.3·3.3.1·3.6.2절).
 *
 * <p><b>MariaDB를 보지 않는다.</b> 계약이 로그아웃의 저장소 판정을 Redis로만 정했다(3.6.2절 「API별 fail-closed 처리」의 로그아웃 행:
 * MariaDB "해당 없음"). 사용자 상태는 폐기의 전제가 아니며, 탈퇴한 계정의 남은 계열도 정리할 수 있어야 한다.
 *
 * <p>성공 조건은 하나다 — <b>계열이 폐기된 상태임을 Redis 정상 응답으로 확인했을 것.</b> 폐기 명령이 실패했거나 결과를 알 수 없으면 {@code 204}로
 * 보고하지 않는다. 서버 상태를 정리하지 못한 채 "로그아웃됐다"고 답하면 사용자는 세션이 살아 있는 줄 모른다.
 *
 * <p>회전된 이전 토큰이 오면 "이미 지난 토큰"으로 넘기지 않고 재사용으로 보아 계열 전체를 폐기한다. 로그아웃 경로로 재사용 탐지를 우회할 수 없어야 한다.
 *
 * <p>플랫폼과 쿠키는 알지 않는다. 삭제 쿠키를 언제 붙일지는 presentation의 몫이다.
 */
public class AuthSessionRevocationService {

  private final RefreshTokenVerifier refreshTokenVerifier;
  private final RefreshTokenStore refreshTokenStore;

  public AuthSessionRevocationService(
      RefreshTokenVerifier refreshTokenVerifier, RefreshTokenStore refreshTokenStore) {
    this.refreshTokenVerifier = refreshTokenVerifier;
    this.refreshTokenStore = refreshTokenStore;
  }

  /**
   * 계열을 폐기한다. 정상 반환은 계열이 폐기된 상태임을 확인했다는 뜻이다.
   *
   * @throws BusinessException 결정적 무효는 {@code INVALID_REFRESH_TOKEN}, 저장소 장애·결과 불확실은 {@code
   *     AUTH_STATE_UNAVAILABLE}
   */
  public void revoke(AuthSessionRevocationCommand command) {
    RefreshTokenVerification.Verified verified = requireVerified(command.refreshToken());
    RefreshTokenLookupResult lookup =
        this.refreshTokenStore.lookup(
            new RefreshTokenRecord(
                verified.userId(),
                verified.familyId(),
                verified.jti(),
                verified.tokenHash(),
                verified.expiresAtEpochSecond()));

    switch (lookup) {
      // 활성 토큰, 회전된 이전 토큰(재사용), 폐기된 토큰 모두 계열이 폐기된 상태가 되도록 만든다.
      case ACTIVE, ROTATED, REVOKED -> requireFamilyClosed(verified);
      // Redis 정상 조회로 이미 폐기가 확인됐다. 추가 명령 없이 멱등하게 끝낸다.
      case FAMILY_REVOKED -> {}
      // 상태가 식별되지 않았다. 폐기할 계열을 특정할 근거가 없다.
      case NOT_FOUND, HASH_MISMATCH -> throw invalidRefreshToken();
      // 저장값 손상과 "ACTIVE지만 계열의 현재 토큰이 아님"(재사용 의심)이 한 값에 섞여 있다.
      // 재사용이 아님을 확정할 수 없으므로 fail-closed로 계열을 닫되, 외부 응답은 상태 불일치의 401이다.
      case MISMATCHED -> {
        requireFamilyClosed(verified);
        throw invalidRefreshToken();
      }
      case UNAVAILABLE -> throw unavailable();
    }
  }

  private RefreshTokenVerification.Verified requireVerified(String compactToken) {
    if (this.refreshTokenVerifier.verify(compactToken)
        instanceof RefreshTokenVerification.Verified verified) {
      return verified;
    }
    // 누락·위조·용도 불일치·만료를 구분하지 않는다.
    throw invalidRefreshToken();
  }

  /**
   * 계열이 폐기된 상태임을 확인한다.
   *
   * <p>{@code NOT_FOUND}도 성공으로 본다. 계열 key가 없다는 것은 Redis가 정상 응답해 알려 준 결정적 결과이고, 계열 없이는 회전이 성립하지 않으므로
   * "그 계열로 더 이상 갱신할 수 없다"는 목적 상태가 이미 달성된 것이다. 여기서 {@code 401}을 내면 직전 조회가 그 계열을 정상 식별한 사실과 어긋난다.
   *
   * @throws BusinessException 명령 실패·결과 불확실은 {@code AUTH_STATE_UNAVAILABLE}
   */
  private void requireFamilyClosed(RefreshTokenVerification.Verified verified) {
    RefreshFamilyRevocationResult result =
        this.refreshTokenStore.revokeFamily(verified.userId(), verified.familyId());
    if (result == RefreshFamilyRevocationResult.UNAVAILABLE) {
      throw unavailable();
    }
  }

  private static BusinessException invalidRefreshToken() {
    return new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
  }

  private static BusinessException unavailable() {
    return new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }
}
