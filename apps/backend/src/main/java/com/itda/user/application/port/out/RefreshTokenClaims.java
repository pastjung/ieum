package com.itda.user.application.port.out;

/**
 * Refresh Token 발급 입력 (API 명세 3.6.1절 Payload claim).
 *
 * <p>Access와 달리 {@code familyId}를 싣고 {@code role}은 싣지 않는다. Refresh는 권한 판단에 쓰이지 않고 회전 계열을 식별하는 용도이기
 * 때문이다.
 *
 * <p>{@code jti}·{@code iat}·{@code exp}는 여기에 담지 않는다 — 서버가 발급 시점에 직접 만든다.
 *
 * <p>{@code toString()}을 재정의해 사용자 식별자와 세대 값이 로그로 새지 않게 한다.
 */
public record RefreshTokenClaims(long userId, String familyId, long authVersion) {

  public RefreshTokenClaims {
    if (userId <= 0) {
      throw new IllegalArgumentException("사용자 식별자가 올바르지 않습니다.");
    }
    // 식별자 규칙은 한 곳에만 둔다. 발급 입력·발급 결과·검증 결과가 같은 형식을 따라야
    // 어느 한쪽만 느슨해져 Redis 키가 되는 값이 새는 일이 없다.
    RefreshTokenRecord.requireFamilyId(familyId);
    if (authVersion < 0) {
      throw new IllegalArgumentException("인증 세대 값이 올바르지 않습니다.");
    }
  }

  @Override
  public String toString() {
    return "RefreshTokenClaims[REDACTED]";
  }
}
