package com.itda.user.application.port.out;

/**
 * 회전 전 Refresh 상태 사전 조회 결과.
 *
 * <p>이 조회는 <b>자원 보호용</b>이다. 서명만 정상인 토큰이 반복 제출될 때 값비싼 회전 경로까지 가지 않게 막을 뿐이며, 정확성의 최종 근거가 아니다. 실제 전이는
 * {@code rotateRefresh} Lua가 같은 조건을 원자적으로 다시 검사하면서 수행한다 (API 명세 3.4.2·3.6.1절).
 *
 * <p>이 조회는 어떤 상태도 바꾸지 않는다. 재사용 탐지와 계열 폐기는 {@code rotateRefresh}만 수행한다.
 */
public enum RefreshTokenLookupResult {
  ACTIVE,
  NOT_FOUND,
  ROTATED,
  REVOKED,
  FAMILY_REVOKED,
  HASH_MISMATCH,
  /**
   * Redis에 저장된 식별자·필드가 검증된 JWT와 어긋난 경우다. 저장 상태가 결정적으로 어긋났는데 ACTIVE로 받아들이면 조작되거나 손상된 상태를 정상 세션으로
   * 인정하게 된다.
   *
   * <p><b>재사용이 아님을 확정하는 값이 아니다.</b> 토큰이 {@code ACTIVE}지만 계열의 {@code currentJti}와 일치하지 않는 상태가 여기에
   * 포함되는데, 같은 상태를 {@code rotateRefresh}는 재사용으로 판정하고 계열을 폐기한다. 이 값만 보고 회전을 건너뛰면 재사용 탐지와 계열 폐기까지 가지
   * 못한다.
   */
  MISMATCHED,
  /** 상태를 읽지 못했거나 결과가 불확실하다. 인증 실패로 단정하지 않는다 (API 명세 3.6.2절 fail-closed). */
  UNAVAILABLE
}
