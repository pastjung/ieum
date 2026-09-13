package com.itda.user.application.port.out;

/**
 * 계정 작업 토큰의 Redis 상태 outbound port (API 명세 3.6.1절, ERD 3.1.1절).
 *
 * <p>세 연산을 한 interface에 두는 이유는 같은 저장소의 같은 키 집합을 다루기 때문이다. 호출자는 Redis 키 문자열·자료구조·Lua 반환 문자열을 전혀 보지
 * 않고 닫힌 결과만 받는다.
 *
 * <p>어떤 메서드도 토큰 원문을 받지 않는다. Redis에 남는 것은 {@code tokenHash}뿐이다.
 */
public interface ActionTokenStore {

  /**
   * 새 토큰을 등록하고 같은 사용자·용도의 이전 활성 토큰을 무효화한다. 이미 있는 {@code jti}는 덮어쓰지 않는다.
   *
   * @param token 만료 시각이 현재보다 뒤여야 한다
   * @throws IllegalArgumentException 만료 시각이 이미 지났을 때. 과거 시각으로 저장하면 Redis가 키를 즉시 지워, 아무 상태도 남기지 못한
   *     처리가 성공으로 보고된다
   */
  ActionTokenIssueResult issueActionToken(ActionTokenRecord token);

  /**
   * 소비 전 자원 보호용 사전 조회. <b>정확성의 최종 근거가 아니며 어떤 상태도 바꾸지 않는다.</b>
   *
   * <p>호출자는 이 결과만으로 소비 가능 여부를 확정하지 않는다. 사전 조회와 소비 사이에 상태가 바뀌면 최종 소비가 실패해야 한다는 것이 계약(3.4.2절)이다.
   *
   * @throws IllegalArgumentException 식별자 형식이 계약과 다를 때
   */
  ActionTokenLookupResult lookup(ActionTokenRecord submitted);

  /**
   * 토큰을 원자적으로 소비한다. 적격 조건 확인과 {@code CONSUMED} 전이, {@code action-current} 정리가 한 연산에서 일어난다.
   *
   * @throws IllegalArgumentException 식별자 형식이 계약과 다를 때
   */
  ActionTokenConsumeResult consume(ActionTokenRecord submitted);
}
