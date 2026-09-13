package com.itda.user.application.port.out;

/**
 * 계정 작업 토큰 원자적 소비 결과 ({@code consumeActionToken}, API 명세 3.6.1절).
 *
 * <p>{@code action-current}가 요청 {@code jti}를 가리키는지, 상태가 {@code ACTIVE}인지, {@code tokenHash}가 일치하는지를
 * 한 연산에서 확인하고 {@code CONSUMED}로 전이한다. 전이에 성공하면 {@code action-current}도 같은 연산에서 정리한다.
 *
 * <p>이 값들은 내부 판정용이다. 계약(3.4.1·3.4.2절)은 불일치 결과를 모두 {@code 400 ACTION_TOKEN_INVALID}로 일반화하므로 HTTP 응답에
 * 그대로 노출하지 않는다.
 */
public enum ActionTokenConsumeResult {
  CONSUMED,
  NOT_FOUND,
  NOT_CURRENT,
  HASH_MISMATCH,
  ALREADY_CONSUMED,
  INVALIDATED,
  UNAVAILABLE
}
