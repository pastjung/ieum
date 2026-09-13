package com.itda.user.application.port.out;

import java.time.Instant;
import java.util.Optional;

/**
 * 제공자 호출 한 번의 결과.
 *
 * <p>결과 종류는 {@link MailSendResult} 다섯 개로 닫혀 있고(API 명세 3.8절), 여기에 <b>제공자가 지시한 재시도 시각</b>만 선택적으로
 * 덧붙인다. ERD 3.5절은 "제공자가 지시한 재시도 시각은 기한 안이면 존중하며, 내부 지수 backoff의 상한은 그 지시를 더 이른 시각으로 앞당기는 근거로 쓰지
 * 않는다"고 정하는데, enum 하나만으로는 그 시각을 worker에게 전달할 방법이 없다.
 *
 * <p><b>제공자 원문 오류·응답 body·예외 message를 담지 않는다.</b> 담을 자리를 만들지 않는 것이 규칙을 지키는 가장 확실한 방법이다 (API 명세 3.8절
 * 금지 label). adapter는 자기 기술 오류를 다섯 값의 의미로 분류하고, 재시도 시각은 제공자가 명시한 경우에만 채운다.
 *
 * <p>시각은 절대 시각({@link Instant})이다. 제공자 응답의 상대 초나 지역 시각을 그대로 옮기지 않고 adapter가 UTC 절대 시각으로 바꿔서 넘긴다.
 */
public record MailSendOutcome(MailSendResult result, Instant providerRetryAt) {

  public MailSendOutcome {
    if (result == null) {
      throw new IllegalArgumentException("제공자 호출 결과가 없습니다.");
    }
    if (providerRetryAt != null && !result.isRetryable()) {
      // 재시도하지 않는 결과에 재시도 시각이 오면 둘 중 하나는 잘못된 분류다. 조용히 버리지 않고 거부한다.
      throw new IllegalArgumentException("재시도하지 않는 결과에는 재시도 시각을 넣을 수 없습니다.");
    }
  }

  /** 제공자가 재시도 시각을 지시하지 않은 결과. */
  public static MailSendOutcome of(MailSendResult result) {
    return new MailSendOutcome(result, null);
  }

  /** 제공자가 재시도 시각을 지시한 결과. 재시도 가능한 결과에만 쓸 수 있다. */
  public static MailSendOutcome retryAt(MailSendResult result, Instant providerRetryAt) {
    if (providerRetryAt == null) {
      throw new IllegalArgumentException("제공자가 지시한 재시도 시각이 없습니다.");
    }
    return new MailSendOutcome(result, providerRetryAt);
  }

  public Optional<Instant> retryAt() {
    return Optional.ofNullable(this.providerRetryAt);
  }
}
