package com.itda.user.application.port.out;

/**
 * 계정 작업 토큰 등록 결과 ({@code issueActionToken}, API 명세 3.6.1절).
 *
 * <p>{@code ALREADY_EXISTS}는 같은 {@code jti}가 이미 있어 덮어쓰기를 거부한 경우다. 새 UUID를 만들어 유한 횟수로 다시 시도하는 책임은 이
 * port가 아니라 호출하는 유스케이스에 있다.
 */
public enum ActionTokenIssueResult {
  ISSUED,
  ALREADY_EXISTS,
  /** 상태를 바꾸지 못했거나 결과가 불확실하다. 성공으로 단정하지 않는다 (API 명세 3.6.2절 fail-closed). */
  UNAVAILABLE
}
