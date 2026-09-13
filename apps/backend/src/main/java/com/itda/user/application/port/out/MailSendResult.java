package com.itda.user.application.port.out;

/**
 * 메일 제공자 호출 결과 (API 명세 3.8절 「제공자 호출 결과의 구분」).
 *
 * <p>다섯 값은 서로 배타적이며 판정 기준은 <b>제공자의 접수 여부</b>다. 각 제공자 adapter가 자기 기술 오류를 이 의미로 분류하며 제공자 원문 오류를 값으로
 * 쓰지 않는다.
 */
public enum MailSendResult {

  /** 제공자가 요청 접수를 명시적으로 확인했다. 편지함 도달을 뜻하지 않는다. */
  ACCEPTED,

  /** 결정적 영구 거부다. 재시도하지 않는다. */
  REJECTED,

  /** 인증·권한·필수 설정 오류다. 기한과 최대 시도 범위 안에서 재시도하되 운영자에게 알린다. */
  AUTH_CONFIG_ERROR,

  /** 제공자가 접수하지 않았음이 확정된 일시적 실패다. 다시 시도할 수 있다. */
  RETRYABLE,

  /**
   * 요청을 보낸 뒤 시간 초과나 연결 단절 등으로 접수 여부를 확정할 수 없다.
   *
   * <p>단순한 시간 초과를 무조건 {@link #RETRYABLE}로 분류하지 않는다. 제공자가 접수하지 않았다는 사실이 확인되지 않으면 이 값이다.
   */
  UNCERTAIN;

  /**
   * 기한과 최대 시도 범위 안에서 다시 시도하는 결과인지.
   *
   * <p>{@link #ACCEPTED}는 끝난 결과고 {@link #REJECTED}는 결정적 영구 거부라 둘 다 다시 시도하지 않는다. 나머지 셋은 ERD 3.5절의
   * 재시도 경로로 간다. 이 구분이 {@link MailSendOutcome}에서 제공자 지정 재시도 시각을 받을 수 있는 결과를 결정한다.
   */
  public boolean isRetryable() {
    return this == AUTH_CONFIG_ERROR || this == RETRYABLE || this == UNCERTAIN;
  }
}
