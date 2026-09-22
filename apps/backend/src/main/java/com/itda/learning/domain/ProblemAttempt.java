package com.itda.learning.domain;

import com.itda.global.braille.BraillePattern;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * problem_attempts — 사용자가 문제를 한 번 풀어 제출한 시도와 최종 채점 요약 (ERD 5.1절).
 *
 * <p>Cell 평가를 컬렉션으로 소유하지 않는다. attempt_cells는 {@link AttemptCell}이 attempt_id FK를 단방향으로 들고 있으므로,
 * Attempt를 저장한 뒤 Cell을 저장하는 것은 호출하는 서비스의 책임이다.
 *
 * <p><b>score·is_correct·diff_mask는 클라이언트 값을 신뢰하지 않고 서버가 계산한다</b>(ERD 5.2절). 그래서 이 클래스에는 그 값을 주입하는
 * 경로가 없다 — 생성자가 Cell 제출 목록에서 직접 계산한다.
 *
 * <p>FREE와 LIVE의 문맥 조합이 서로 배타적이므로(ERD 5.1절) 팩토리를 두 개로 나눠 잘못된 조합이 애초에 만들어지지 않게 했다.
 */
@Entity
@Table(
    name = "problem_attempts",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_problem_attempts_idempotency_key",
          columnNames = {"idempotency_key"}),
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

  /** ERD 5.2절: score는 소수점 넷째 자리로 반올림한다 (DECIMAL(5,4)). */
  private static final int SCORE_SCALE = 4;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "problem_id", nullable = false)
  private Long problemId;

  /** 개인 학습·복습 세션 learning_sessions.id. LIVE에서는 NULL이다. 다른 애그리거트이므로 Long으로 둔다. */
  @Column(name = "learning_session_id")
  private Long learningSessionId;

  /** assigned_problems.item_no 연결. LIVE에서는 NULL이다. */
  @Column(name = "session_item_no")
  private Integer sessionItemNo;

  /** FREE에서는 세션 기준 단계, LIVE에서는 NULL이다. */
  @Column(name = "curriculum_stage_id")
  private Long curriculumStageId;

  /** LIVE에서는 필수, FREE에서는 NULL이다. */
  @Column(name = "lecture_id")
  private Long lectureId;

  @Enumerated(EnumType.STRING)
  @Column(name = "attempt_mode", nullable = false, length = 10)
  private AttemptMode attemptMode;

  @Column(name = "is_correct", nullable = false)
  private boolean correct;

  /** 점 단위 정확도 0~1. is_correct=true는 score가 1일 때만 가능하다. */
  @Column(nullable = false, precision = 5, scale = SCORE_SCALE)
  private BigDecimal score;

  /** 입력 가능 시점부터 최종 제출까지 클라이언트 단조 시계로 측정. 측정 불가를 0으로 대체하지 않으며 BKT에 직접 쓰지 않는다. */
  @Column(name = "duration_ms")
  private Integer durationMs;

  /** 정상 최종 제출을 접수한 서버 DB 시각. Cell responded_at과 같은 값이다. */
  @Column(name = "attempted_at", nullable = false)
  private LocalDateTime attemptedAt;

  /** UUID v4 멱등 키. 동일 제출 재시도의 중복 저장·중복 분석을 막는다. */
  @Column(name = "idempotency_key", nullable = false, columnDefinition = "CHAR(36)")
  private String idempotencyKey;

  private ProblemAttempt(
      Long userId,
      Long problemId,
      Long learningSessionId,
      Integer sessionItemNo,
      Long curriculumStageId,
      Long lectureId,
      AttemptMode attemptMode,
      List<CellSubmission> submissions,
      Integer durationMs,
      String idempotencyKey,
      LocalDateTime now) {
    if (userId == null || problemId == null) {
      throw new IllegalArgumentException("사용자와 문제는 모두 필수입니다.");
    }
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("멱등 키는 필수입니다.");
    }
    if (now == null) {
      throw new IllegalArgumentException("제출 시각은 필수입니다.");
    }
    if (durationMs != null && durationMs < 0) {
      throw new IllegalArgumentException("풀이 시간은 0 이상이어야 합니다: " + durationMs);
    }
    if (submissions == null || submissions.isEmpty()) {
      throw new IllegalArgumentException("Cell 제출은 최소 1개여야 합니다.");
    }

    this.userId = userId;
    this.problemId = problemId;
    this.learningSessionId = learningSessionId;
    this.sessionItemNo = sessionItemNo;
    this.curriculumStageId = curriculumStageId;
    this.lectureId = lectureId;
    this.attemptMode = attemptMode;
    this.durationMs = durationMs;
    this.idempotencyKey = idempotencyKey;
    this.attemptedAt = now;

    int errorDots = 0;
    for (int index = 0; index < submissions.size(); index++) {
      CellSubmission submission = submissions.get(index);
      if (submission == null) {
        throw new IllegalArgumentException("Cell 제출은 비어 있을 수 없습니다: " + (index + 1) + "번째");
      }
      errorDots += submission.expectedPattern().diff(submission.inputPattern()).dotCount();
    }
    int totalDots = submissions.size() * BraillePattern.DOTS_PER_CELL;
    this.score =
        BigDecimal.valueOf(totalDots - errorDots)
            .divide(BigDecimal.valueOf(totalDots), SCORE_SCALE, RoundingMode.HALF_UP);
    // 모든 diff_mask가 0일 때만 문제 전체 정답이다 (ERD 5.2절).
    this.correct = errorDots == 0;
  }

  /**
   * 개인 학습·복습(FREE) 풀이. 세션 연결 두 컬럼이 함께 필수이고 lecture_id는 NULL이다 (ERD 5.1절).
   *
   * @param submissions Cell 순서대로 나열한 제출. 목록 순서가 cell_index 1..N이 된다
   */
  public static ProblemAttempt forFreeSession(
      Long userId,
      Long problemId,
      Long learningSessionId,
      Integer sessionItemNo,
      Long curriculumStageId,
      List<CellSubmission> submissions,
      Integer durationMs,
      String idempotencyKey,
      LocalDateTime now) {
    if (learningSessionId == null || sessionItemNo == null) {
      throw new IllegalArgumentException("개인 학습 풀이는 세션과 배정 순번이 함께 필수입니다.");
    }
    if (sessionItemNo < 1) {
      throw new IllegalArgumentException("배정 순번은 1 이상이어야 합니다: " + sessionItemNo);
    }
    if (curriculumStageId == null) {
      throw new IllegalArgumentException("개인 학습 풀이는 기준 단계가 필수입니다.");
    }
    return new ProblemAttempt(
        userId,
        problemId,
        learningSessionId,
        sessionItemNo,
        curriculumStageId,
        null,
        AttemptMode.FREE,
        submissions,
        durationMs,
        idempotencyKey,
        now);
  }

  /**
   * 실시간 수업(LIVE) 풀이. lecture_id가 필수이고 세션 연결 두 컬럼과 기준 단계는 NULL이다 (ERD 5.1절).
   *
   * <p>LIVE는 BKT와 user_skill_memory 갱신에서 제외된다. 저장된 오답은 수업 종료 후 개인 FREE 세션의 문제 후보로만 쓰인다.
   */
  public static ProblemAttempt forLiveLecture(
      Long userId,
      Long problemId,
      Long lectureId,
      List<CellSubmission> submissions,
      Integer durationMs,
      String idempotencyKey,
      LocalDateTime now) {
    if (lectureId == null) {
      throw new IllegalArgumentException("실시간 수업 풀이는 수업이 필수입니다.");
    }
    return new ProblemAttempt(
        userId,
        problemId,
        null,
        null,
        null,
        lectureId,
        AttemptMode.LIVE,
        submissions,
        durationMs,
        idempotencyKey,
        now);
  }

  public boolean isCorrect() {
    return correct;
  }
}
