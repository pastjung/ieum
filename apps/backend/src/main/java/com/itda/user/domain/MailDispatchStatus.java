package com.itda.user.domain;

/**
 * 메일 발송 작업의 상태 (ERD 3.5절).
 *
 * <p>처리 중을 뜻하는 상태를 두지 않는다. 선점된 행과 대기 중인 행은 {@code status}로 구분되지 않고 {@code next_attempt_at}이 미래인지로
 * 근사한다. 구조를 줄이는 대신 관측 정밀도를 낮춘 선택이라고 계약이 명시한다.
 */
public enum MailDispatchStatus {

  /** 최초 대기, backoff 대기, 가시성 만료 뒤 재처리 대기를 모두 포함한다. */
  PENDING,

  /** 제공자가 요청을 접수했다. <b>사용자 편지함 도달을 보장하지 않는다</b> (API 명세 3.8절). */
  PROVIDER_ACCEPTED,

  /** 계정 부재·비활성·이미 인증됨처럼 발송 대상이 아니어서 끝난 정상 결과다. */
  SKIPPED,

  /** 재시도를 종료했다. 메일이 절대 전달되지 않았다는 증명은 아니다. */
  FAILED;

  /** 종결 상태는 다시 {@link #PENDING}으로 되돌리지 않는다. */
  public boolean isTerminal() {
    return this != PENDING;
  }
}
