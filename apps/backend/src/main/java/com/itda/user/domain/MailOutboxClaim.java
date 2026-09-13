package com.itda.user.domain;

import java.time.LocalDateTime;

/**
 * 한 worker가 선점한 작업의 문맥 (ERD 3.5절).
 *
 * <p>선점에 성공한 worker는 증가한 {@code attemptCount}와 <b>자신이 기록한</b> {@code visibilityExpiresAt}을 이후 갱신의
 * 조건으로 쓴다. 가시성이 지나 다른 worker가 다시 선점하면 두 값이 모두 바뀌므로 앞선 worker의 늦은 갱신은 0행으로 실패한다.
 *
 * <p>{@code visibilityExpiresAt}은 재시도·종결 UPDATE에서 현재 시각으로 다시 계산하지 않고 선점 때 전달한 값을 그대로 쓴다. 조건이 시각의
 * 정확한 일치에 의존하기 때문이다.
 */
public record MailOutboxClaim(
    Long id,
    AccountActionPurpose purpose,
    String recipientEmail,
    int attemptCount,
    LocalDateTime previousNextAttemptAt,
    LocalDateTime visibilityExpiresAt,
    LocalDateTime deliveryDeadlineAt) {

  public MailOutboxClaim {
    if (id == null) {
      throw new IllegalArgumentException("작업 식별자가 없습니다.");
    }
    if (purpose == null) {
      throw new IllegalArgumentException("발송 용도가 없습니다.");
    }
    if (attemptCount <= 0) {
      throw new IllegalArgumentException("선점한 작업의 시도 횟수는 1 이상이어야 합니다.");
    }
  }

  /** 수신 주소는 로그·예외로 나가면 안 된다. 작업 식별자와 용도만 남긴다 (ERD 3.5절 로그 규칙). */
  @Override
  public String toString() {
    return "MailOutboxClaim[id=" + this.id + ", purpose=" + this.purpose + "]";
  }
}
