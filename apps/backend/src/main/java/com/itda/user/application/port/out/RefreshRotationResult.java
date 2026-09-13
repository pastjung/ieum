package com.itda.user.application.port.out;

/**
 * Refresh 원자 회전 결과 ({@code rotateRefresh}).
 *
 * <p>{@code REUSED}는 이미 회전·폐기된 토큰이 다시 제출됐거나, ACTIVE 토큰이지만 계열의 현재 토큰이 아닌 경우다. 두 경우 모두 같은 스크립트 안에서
 * 계열을 폐기한 뒤 이 값을 돌려준다 — 탐지와 폐기 사이에 다른 요청이 끼어들지 못하게 하기 위해서다.
 *
 * <p>{@code ALREADY_EXISTS}는 신규 {@code jti} 키가 이미 있어 아무 상태도 바꾸지 않은 경우다. 새 식별자로 다시 시도할지는 호출하는 유스케이스가
 * 정한다.
 *
 * <p>이 값들은 내부 판정용이다. HTTP 응답 body·헤더에 노출하지 않는다 (API 명세 3.6.1절 「내부 사유의 비노출」).
 */
public enum RefreshRotationResult {
  ROTATED,
  NOT_FOUND,
  HASH_MISMATCH,
  REUSED,
  FAMILY_REVOKED,
  ALREADY_EXISTS,
  UNAVAILABLE
}
