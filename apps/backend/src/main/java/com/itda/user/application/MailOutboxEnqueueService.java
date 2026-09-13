package com.itda.user.application;

import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.Email;
import com.itda.user.domain.MailOutboxEntry;
import com.itda.user.domain.MailOutboxRepository;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 메일 발송 작업 접수 (API 명세 3.4.1·3.4.2절, ERD 3.5절).
 *
 * <p>계정을 조회하지 않는다. 요청 API가 계정 존재 여부에 따라 다른 일을 하면 응답으로 그 사실이 드러난다. 발송 적격성 판정은 전부 비동기 worker의 몫이다.
 *
 * <p>트랜잭션을 스스로 열지 않는다. 회원가입은 {@code users} INSERT와 이 작업 등록을 같은 트랜잭션으로 커밋해야 하고, 두 요청 API는 자기 트랜잭션
 * 안에서 호출한다. 여기서 새 트랜잭션을 강제하면 한쪽만 커밋되는 상태가 생긴다.
 */
public class MailOutboxEnqueueService {

  /** ERD 3.5절: 발송 기한은 PASSWORD_RESET 30분, EMAIL_VERIFICATION 24시간. */
  private static final Duration EMAIL_VERIFICATION_DEADLINE = Duration.ofHours(24);

  private static final Duration PASSWORD_RESET_DEADLINE = Duration.ofMinutes(30);

  private final MailOutboxRepository mailOutboxRepository;

  public MailOutboxEnqueueService(MailOutboxRepository mailOutboxRepository) {
    this.mailOutboxRepository = mailOutboxRepository;
  }

  /**
   * 작업을 접수한다. 호출자의 트랜잭션에 참여한다.
   *
   * <p><b>저장 전에 {@link Email}로 정규화·검증한다.</b> worker는 수신 주소를 {@code Email.of}로 다시 읽어야 진행하므로, 형식이 어긋난
   * 주소가 저장되면 선점할 때마다 예외로 끝나 상태 전이도 실패 분류도 남기지 못한 채 시도 횟수만 상한까지 태운다. 그 상태는 운영자가 원인을 알 수 없다. 잘못된 주소는
   * 여기서, {@code repository.save} 이전에 끝낸다.
   *
   * @param recipientEmail 원문 수신 주소. trim·소문자 정규화는 여기서 한다
   * @param now 접수 시각. 호출자가 한 트랜잭션의 기준 시각을 넘긴다
   * @throws IllegalArgumentException 이메일 형식이 올바르지 않거나 기한이 접수 시각 이하일 때
   */
  public MailOutboxEntry enqueue(
      AccountActionPurpose purpose, String recipientEmail, LocalDateTime now) {
    String normalized = Email.of(recipientEmail).getValue();
    LocalDateTime deadline = now.plus(deadlineFor(purpose));
    return this.mailOutboxRepository.save(
        MailOutboxEntry.enqueue(purpose, normalized, deadline, now));
  }

  private static Duration deadlineFor(AccountActionPurpose purpose) {
    return switch (purpose) {
      case EMAIL_VERIFICATION -> EMAIL_VERIFICATION_DEADLINE;
      case PASSWORD_RESET -> PASSWORD_RESET_DEADLINE;
    };
  }
}
