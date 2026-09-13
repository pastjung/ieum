package com.itda.user.domain;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계정 메일 발송 작업 (ERD 3.5절 {@code mail_outbox}).
 *
 * <p>업무 상태를 담지 않는 인프라 테이블이다. 요청 API는 계정을 조회하지 않고 작업만 남기므로 응답 시간으로 계정 존재 여부가 드러나지 않는다.
 *
 * <p><b>저장하지 않는 것</b>: 메일 본문, 인증 토큰, {@code tokenHash}, 비밀번호, 사용자 식별자. 계정 식별자 대신 수신 주소만 남기므로 탈퇴·계정
 * 삭제와 독립적으로 정리된다. FK와 UNIQUE를 두지 않는다 — 같은 주소의 중복 요청을 막지 않는 것이 계약이다.
 *
 * <p>ERD 3.5절의 CHECK 네 개를 {@link CheckConstraint}로 스키마에 만든다. 상태 전이가 조건부 UPDATE로 여러 컬럼을 한꺼번에 바꾸므로,
 * Java 불변식만으로는 엔티티를 거치지 않는 갱신을 막지 못한다. 뒤의 두 불변식은 여러 컬럼을 함께 보므로 계약대로 테이블 레벨 명명 제약으로 둔다. Hibernate의
 * {@code @Check}는 7.2에서 deprecated이므로 JPA 3.2 표준 annotation을 쓴다.
 *
 * <p><b>{@code ddl-auto: validate}가 이 제약들의 존재를 확인해 주지는 않는다.</b> Hibernate의 스키마 검증은 테이블·컬럼·타입까지만 본다.
 * 운영 스키마에 네 CHECK가 실제로 있는지는 배포 시 {@code information_schema.CHECK_CONSTRAINTS}로 따로 확인해야 한다.
 *
 * <p>모든 시각은 {@code DATETIME(6)}에 맞춰 마이크로초로 절삭해 다룬다. 조건부 선점이 시각의 정확한 일치에 의존하기 때문이다 (ERD 3.5절).
 */
@Entity
@Table(
    name = "mail_outbox",
    indexes = {
      @Index(name = "ix_mail_outbox_status_next_attempt", columnList = "status, next_attempt_at"),
      @Index(name = "ix_mail_outbox_status_deadline", columnList = "status, delivery_deadline_at"),
      @Index(name = "ix_mail_outbox_status_completed", columnList = "status, completed_at")
    },
    check = {
      @CheckConstraint(name = "ck_mail_outbox_attempt_count", constraint = "attempt_count >= 0"),
      @CheckConstraint(
          name = "ck_mail_outbox_delivery_deadline",
          constraint = "delivery_deadline_at > created_at"),
      @CheckConstraint(
          name = "ck_mail_outbox_pending_state",
          constraint =
              """
              status <> 'PENDING'
                or (recipient_email is not null and next_attempt_at is not null\
               and completed_at is null)\
              """),
      @CheckConstraint(
          name = "ck_mail_outbox_terminal_state",
          constraint =
              """
              status = 'PENDING'
                or (recipient_email is null and next_attempt_at is null\
               and completed_at is not null)\
              """)
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MailOutboxEntry {

  /** ERD 3.5절 {@code recipient_email} VARCHAR(254). */
  public static final int RECIPIENT_EMAIL_MAX_LENGTH = 254;

  /** ERD 3.5절 {@code last_error_kind} VARCHAR(40). 닫힌 열거가 아니라 정제된 내부 분류 문자열이다. */
  public static final int LAST_ERROR_KIND_MAX_LENGTH = 40;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private AccountActionPurpose purpose;

  /** 종결 전이와 같은 UPDATE에서 NULL로 지운다. 재시도 전이에서는 유지한다. */
  @Column(name = "recipient_email", length = RECIPIENT_EMAIL_MAX_LENGTH)
  private String recipientEmail;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private MailDispatchStatus status;

  /** worker가 선점에 성공한 횟수. 조회·토큰 발급 단계에서 실패해도 증가하므로 제공자 호출 횟수보다 클 수 있다. */
  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  /** 다음 처리 가능 시각이자 선점한 작업의 가시성 만료 시각. PENDING에서만 값을 가진다. */
  @Column(name = "next_attempt_at")
  private LocalDateTime nextAttemptAt;

  /** 접수 시각 기준 발송 기한. PASSWORD_RESET 30분, EMAIL_VERIFICATION 24시간. */
  @Column(name = "delivery_deadline_at", nullable = false)
  private LocalDateTime deliveryDeadlineAt;

  /** 종결 시각. 종결 상태에서만 값을 가지며 보존 기간 정리의 기준이다. */
  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  /** 실패 분류값. 제공자 원문 오류는 저장하지 않는다. */
  @Column(name = "last_error_kind", length = LAST_ERROR_KIND_MAX_LENGTH)
  private String lastErrorKind;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  private MailOutboxEntry(
      AccountActionPurpose purpose,
      String recipientEmail,
      LocalDateTime deliveryDeadlineAt,
      LocalDateTime now) {
    this.purpose = purpose;
    this.recipientEmail = recipientEmail;
    this.status = MailDispatchStatus.PENDING;
    this.attemptCount = 0;
    // 최초 처리는 접수 직후다 (ERD 3.5절).
    this.nextAttemptAt = now;
    this.deliveryDeadlineAt = deliveryDeadlineAt;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /**
   * 새 발송 작업을 접수한다.
   *
   * @param recipientEmail 정규화한 수신 주소
   * @param deliveryDeadlineAt 접수 시각 기준 발송 기한. {@code now}보다 뒤여야 한다
   * @param now 접수 시각. 호출자가 넘긴다 — 엔티티가 시계를 직접 읽으면 한 트랜잭션의 여러 시각이 어긋난다
   */
  public static MailOutboxEntry enqueue(
      AccountActionPurpose purpose,
      String recipientEmail,
      LocalDateTime deliveryDeadlineAt,
      LocalDateTime now) {
    if (purpose == null) {
      throw new IllegalArgumentException("발송 용도가 없습니다.");
    }
    if (recipientEmail == null || recipientEmail.isBlank()) {
      throw new IllegalArgumentException("수신 주소가 없습니다.");
    }
    if (recipientEmail.length() > RECIPIENT_EMAIL_MAX_LENGTH) {
      // 값 자체를 message에 넣지 않는다.
      throw new IllegalArgumentException("수신 주소가 허용 길이를 넘습니다.");
    }
    if (now == null || deliveryDeadlineAt == null || !deliveryDeadlineAt.isAfter(now)) {
      throw new IllegalArgumentException("발송 기한이 접수 시각보다 뒤여야 합니다.");
    }
    return new MailOutboxEntry(
        purpose, recipientEmail, truncate(deliveryDeadlineAt), truncate(now));
  }

  /** {@code DATETIME(6)}에 맞게 마이크로초로 절삭한다. 조건부 선점이 시각의 정확한 일치에 의존한다. */
  public static LocalDateTime truncate(LocalDateTime value) {
    return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
  }
}
