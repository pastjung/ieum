package com.itda.user.application.port.out;

/**
 * 유출 비밀번호 조회 결과 (API 명세 3.6.2절 「유출 비밀번호 조회 장애」).
 *
 * <p>{@code UNAVAILABLE}을 별도 값으로 두는 것이 이 타입의 핵심이다. 조회하지 못한 상태를 {@code NOT_COMPROMISED}로 접으면 장애 중에
 * 유출된 비밀번호가 그대로 통과하는 fail-open이 된다. 계약은 fail-closed이며 이 값은 {@code 503 PASSWORD_POLICY_UNAVAILABLE}로
 * 이어진다.
 */
public enum CompromisedPasswordCheckResult {
  NOT_COMPROMISED,
  COMPROMISED,
  UNAVAILABLE
}
