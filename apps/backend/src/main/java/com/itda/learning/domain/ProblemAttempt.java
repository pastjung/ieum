package com.itda.learning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 사용자의 문제 단위 풀이 시도와 최종 정오답을 저장한다. */
@Entity
@Table(
    name = "problem_attempts",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_problem_attempts_session_item",
          columnNames = {"learning_session_id", "session_item_no"}),
      @UniqueConstraint(
          name = "uk_problem_attempts_lecture_user_problem",
          columnNames = {"lecture_id", "user_id", "problem_id"})
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemAttempt {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "problem_id", nullable = false)
  private Long problemId;

  @Column(name = "learning_session_id")
  private Long learningSessionId;

  @Column(name = "session_item_no")
  private Integer sessionItemNo;

  @Column(name = "curriculum_stage_id")
  private Long curriculumStageId;

  @Column(name = "lecture_id")
  private Long lectureId;

  @Enumerated(EnumType.STRING)
  @Column(name = "attempt_mode", nullable = false, length = 10)
  private AttemptMode attemptMode;

  @Column(name = "is_correct", nullable = false)
  private boolean correct;

  @Column(name = "attempted_at", nullable = false)
  private LocalDateTime attemptedAt;

  private ProblemAttempt(
      Long userId,
      Long problemId,
      Long learningSessionId,
      Integer sessionItemNo,
      Long curriculumStageId,
      Long lectureId,
      AttemptMode attemptMode,
      boolean correct,
      LocalDateTime attemptedAt) {
    if (userId == null || problemId == null) {
      throw new IllegalArgumentException("사용자와 문제는 필수입니다.");
    }
    if (attemptedAt == null) {
      throw new IllegalArgumentException("풀이 시각은 필수입니다.");
    }

    this.userId = userId;
    this.problemId = problemId;
    this.learningSessionId = learningSessionId;
    this.sessionItemNo = sessionItemNo;
    this.curriculumStageId = curriculumStageId;
    this.lectureId = lectureId;
    this.attemptMode = attemptMode;
    this.correct = correct;
    this.attemptedAt = attemptedAt;
  }

  public static ProblemAttempt forFreeSession(
      Long userId,
      Long problemId,
      Long learningSessionId,
      Integer sessionItemNo,
      Long curriculumStageId,
      boolean correct,
      LocalDateTime attemptedAt) {
    if (learningSessionId == null || sessionItemNo == null) {
      throw new IllegalArgumentException("개인 학습 시도에는 세션과 배정 순서가 필수입니다.");
    }
    if (sessionItemNo < 1) {
      throw new IllegalArgumentException("배정 순서는 1 이상이어야 합니다: " + sessionItemNo);
    }
    return new ProblemAttempt(
        userId,
        problemId,
        learningSessionId,
        sessionItemNo,
        curriculumStageId,
        null,
        AttemptMode.FREE,
        correct,
        attemptedAt);
  }

  public static ProblemAttempt forLiveLecture(
      Long userId,
      Long problemId,
      Long lectureId,
      boolean correct,
      LocalDateTime attemptedAt) {
    if (lectureId == null) {
      throw new IllegalArgumentException("실시간 수업 시도에는 수업이 필수입니다.");
    }
    return new ProblemAttempt(
        userId,
        problemId,
        null,
        null,
        null,
        lectureId,
        AttemptMode.LIVE,
        correct,
        attemptedAt);
  }

  public boolean isCorrect() {
    return correct;
  }
}
