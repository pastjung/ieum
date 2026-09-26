package com.itda.user.domain;

/**
 * 새 비밀번호가 결정적으로 정책을 위반한 이유 (API 명세 3.5절 {@code 422 INVALID_PASSWORD}).
 *
 * <p>흔한 값과 유출 값을 나누지 않는다. 외부 계약이 둘을 같은 code로 응답하고, 판정 근거도 Pwned Passwords 단일 blocklist 하나이기 때문이다.
 *
 * <p>유출 조회가 실패한 경우는 이 enum의 값이 아니다. 그것은 정책 위반이 아니라 정책을 완성하지 못한 상태이며 {@code 503
 * PASSWORD_POLICY_UNAVAILABLE}로 구분한다.
 */
public enum PasswordPolicyViolation {
  TOO_SHORT,
  TOO_LONG,
  COMPROMISED
}
