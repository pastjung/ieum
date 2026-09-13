package com.itda.user.application;

import java.time.Duration;

/**
 * 메일 발송 재시도·가시성·보존 정책 (ERD 3.5절).
 *
 * <p>application이 설정 구현체를 직접 알지 않도록 필요한 값만 추린 경계다. 실제 값은 infrastructure의 설정 record가 제공하며, 테스트는 고정
 * 값을 넘겨 지터 없이 결정적으로 검증한다.
 */
public interface MailDispatchPolicy {

  /** worker 처리 시도 상한. 계약 기본값은 6이다. */
  int maxAttempts();

  /** {@code attemptCount}번째 시도가 실패했을 때의 기본 대기 시간. 지터를 적용하기 전 값이다. */
  Duration backoffFor(int attemptCount);

  /** 대기 시간에 적용할 지터 비율. 계약 기본값은 ±20%다. */
  double jitterRatio();

  /** 선점한 작업의 가시성 만료. 계약 기본값은 5분이다. */
  Duration visibilityTimeout();

  /** 종결 행 보존 기간. 계약 기본값은 7일이다. */
  Duration terminalRetention();
}
