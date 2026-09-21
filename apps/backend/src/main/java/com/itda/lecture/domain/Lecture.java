package com.itda.lecture.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * lectures — 실제로 운영하는 수업 하나 (ERD 3.2절). 강좌·차시의 상위 계층은 없다.
 *
 * <p>강사 시작 전 학습자 입장·대기는 허용하지만 입장 자체가 수업 시작은 아니다.
 */
@Entity
@Table(name = "lectures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Lecture {

  /** ERD 3.2절: 앞자리 0을 허용하는 숫자 6자리 참여 코드. */
  public static final int PARTICIPATION_CODE_LENGTH = 6;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 담당 강사 users.id. user 컨텍스트를 참조하므로 Long으로 둔다. INSTRUCTOR 역할 검증은 서비스가 한다. */
  @Column(name = "instructor_user_id", nullable = false)
  private Long instructorUserId;

  @Column(nullable = false, length = 150)
  private String title;

  @Column(columnDefinition = "TEXT")
  private String description;

  /** 백엔드가 생성하며 전체에서 UNIQUE다. MVP에서는 재사용·재발급하지 않는다. */
  @Column(
      name = "participation_code",
      nullable = false,
      unique = true,
      columnDefinition = "CHAR(6)")
  private String participationCode;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private LectureStatus status;

  /** 클라이언트 입력을 UTC로 정규화한 예정 시각. 실제 시작 시각과 구분한다. */
  @Column(name = "scheduled_at")
  private LocalDateTime scheduledAt;

  @Column(name = "started_at")
  private LocalDateTime startedAt;

  @Column(name = "ended_at")
  private LocalDateTime endedAt;

  /**
   * 현재 전달 중인 lecture_problems.id. OPEN에서만 값을 가질 수 있다.
   *
   * <p>같은 애그리거트지만 객체 참조가 아니라 Long이다. lectures ↔ lecture_problems가 순환 FK(ERD 3.2·3.4절)여서 양방향 객체 참조로는
   * insert 순서를 결정할 수 없다. 같은 수업의 연결 행인지는 서비스가 검증한다.
   */
  @Column(name = "current_lecture_problem_id")
  private Long currentLectureProblemId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  private Lecture(
      Long instructorUserId,
      String title,
      String description,
      String participationCode,
      LocalDateTime scheduledAt,
      LocalDateTime now) {
    this.instructorUserId = requireNonNull(instructorUserId, "담당 강사");
    this.title = requireText(title, "제목");
    this.description = description;
    this.participationCode = requireParticipationCode(participationCode);
    this.scheduledAt = scheduledAt;
    this.status = LectureStatus.SCHEDULED;
    this.createdAt = requireNonNull(now, "생성 시각");
  }

  /** 수업을 만든다. 항상 SCHEDULED로 시작한다. */
  public static Lecture schedule(
      Long instructorUserId,
      String title,
      String description,
      String participationCode,
      LocalDateTime scheduledAt,
      LocalDateTime now) {
    return new Lecture(instructorUserId, title, description, participationCode, scheduledAt, now);
  }

  /** 제목·설명·예정 시각 수정은 SCHEDULED에서만 허용한다 (ERD 3.2절). */
  public void modifyPlan(String newTitle, String newDescription, LocalDateTime newScheduledAt) {
    if (status != LectureStatus.SCHEDULED) {
      throw new IllegalStateException("시작 전 수업만 수정할 수 있습니다.");
    }
    this.title = requireText(newTitle, "제목");
    this.description = newDescription;
    this.scheduledAt = newScheduledAt;
  }

  /** 수업 시작. 시작 시각은 최초 한 번만 기록한다. */
  public void open(LocalDateTime now) {
    if (status != LectureStatus.SCHEDULED) {
      throw new IllegalStateException("시작 전 수업만 시작할 수 있습니다.");
    }
    this.status = LectureStatus.OPEN;
    this.startedAt = requireNonNull(now, "시작 시각");
  }

  /** 수업 종료. ERD 3.2절대로 CLOSED로 바꿀 때 현재 문제를 NULL로 만든다. */
  public void close(LocalDateTime now) {
    if (status != LectureStatus.OPEN) {
      throw new IllegalStateException("진행 중인 수업만 종료할 수 있습니다.");
    }
    this.status = LectureStatus.CLOSED;
    this.endedAt = requireNonNull(now, "종료 시각");
    this.currentLectureProblemId = null;
  }

  /**
   * 현재 전달 중인 문제를 바꾼다. OPEN에서만 가능하다.
   *
   * @param lectureProblemId 이 수업에 연결된 lecture_problems.id — 소속 검증은 서비스 책임이다
   */
  public void deliverProblem(Long lectureProblemId) {
    if (status != LectureStatus.OPEN) {
      throw new IllegalStateException("진행 중인 수업만 문제를 전달할 수 있습니다.");
    }
    this.currentLectureProblemId = requireNonNull(lectureProblemId, "전달할 수업 문제");
  }

  public boolean isOpen() {
    return status == LectureStatus.OPEN;
  }

  /** 인증을 마친 LEARNER는 SCHEDULED·OPEN 수업에 참여할 수 있고 CLOSED에서는 거부된다 (ERD 3.2절). */
  public boolean isJoinable() {
    return status != LectureStatus.CLOSED;
  }

  private static String requireParticipationCode(String value) {
    if (value == null || value.length() != PARTICIPATION_CODE_LENGTH) {
      throw new IllegalArgumentException("참여 코드는 숫자 " + PARTICIPATION_CODE_LENGTH + "자리여야 합니다.");
    }
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isDigit(value.charAt(i))) {
        throw new IllegalArgumentException("참여 코드는 숫자만 포함해야 합니다: " + value);
      }
    }
    return value;
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
    }
    return value;
  }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
    }
    return value;
  }
}
