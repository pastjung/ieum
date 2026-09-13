package com.itda.user.application.port.out;

/**
 * 소비 전 계정 작업 토큰 상태 사전 조회 결과.
 *
 * <p>이 조회는 <b>자원 보호용</b>이다. 값비싼 검사(비밀번호 정책·Argon2id)를 반복 유발하지 못하게 막을 뿐이며 정확성의 최종 근거가 아니다(API 명세
 * 3.4.2절). 실제 전이는 {@code consumeActionToken}이 같은 조건을 원자적으로 다시 검사하면서 수행한다.
 *
 * <p>이 조회는 어떤 상태도 바꾸지 않는다.
 */
public enum ActionTokenLookupResult {
  ACTIVE,
  NOT_FOUND,
  /** {@code action-current}가 이 {@code jti}를 가리키지 않는다. 재발급으로 밀려난 토큰이다. */
  NOT_CURRENT,
  CONSUMED,
  INVALIDATED,
  HASH_MISMATCH,
  /** 저장된 식별자·필드가 검증된 JWT와 어긋난다. 손상되거나 조작된 기록을 정상으로 인정하지 않는다. */
  MISMATCHED,
  UNAVAILABLE
}
