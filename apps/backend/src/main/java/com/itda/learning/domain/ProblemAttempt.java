package com.itda.learning.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * problem_attempts — 사용자가 문제를 한 번 풀어 제출한 시도와 최종 채점 요약 (ERD 5.1절).
 *
 * <p>애그리거트 루트로서 Cell 평가와 요소 평가를 함께 소유한다. ERD 5.5절이 "problem_attempts와 필요한 attempt_cells
 * ·attempt_skill_results가 모두 저장되어야 완료 문항으로 센다"고 규정하므로 cascade가 그 계약과 일치한다.
 *
 * <p><b>is_correct·diff_mask는 클라이언트 값을 신뢰하지 않고 서버가 계산한다</b>. 그래서 이 클래스에는 그 값을 주입하는 경로가 없다 — 생성자가
 * Cell 제출 목록에서 직접 계산한다.
 *
 * <p>FREE와 LIVE의 문맥 조합이 서로 배타적이므로(ERD 5.1절) 팩토리를 두 개로 나눠 잘못된 조합이 애초에 만들어지지 않게 했다.
 */
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

  /** 개인 학습·복습 세션 learning_sessions.id. LIVE에서는 NULL이다. 다른 애그리거트이므로 Long으로 둔다. */
  @Column(name = "learning_session_id")
  private Long learningSessionId;

  /** assigned_problems.item_no 연결. LIVE에서는 NULL이다. */
  @Column(name = "session_item_no")
  private Integer sessionItemNo;

  /** 일반 학습 FREE에서는 세션 기준 단계, 복습 FREE와 LIVE에서는 NULL이다. */
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

  /** 정상 최종 제출을 접수한 서버 DB 시각. Cell responded_at·요소 evaluated_at과 같은 값이다. */
  @Column(name = "attempted_at", nullable = false)
  private LocalDateTime attemptedAt;

  @OneToMany(
      mappedBy = "attempt",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @OrderBy("cellIndex ASC")
  private List<AttemptCell> cells = new ArrayList<>();

  @OneToMany(
      mappedBy = "attempt",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  private List<AttemptSkillResult> skillResults = new ArrayList<>();

  private ProblemAttempt(
      Long userId,
      Long problemId,
      Long learningSessionId,
      Integer sessionItemNo,
      Long curriculumStageId,
      Long lectureId,
      AttemptMode attemptMode,
      List<CellSubmission> submissions,
      LocalDateTime now) {
    if (userId == null || problemId == null) {
      throw new IllegalArgumentException("사용자와 문제는 모두 필수입니다.");
    }
    if (now == null) {
      throw new IllegalArgumentException("제출 시각은 필수입니다.");
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
    this.attemptedAt = now;

    for (int index = 0; index < submissions.size(); index++) {
      CellSubmission submission = submissions.get(index);
      if (submission == null) {
        throw new IllegalArgumentException("Cell 제출은 비어 있을 수 없습니다: " + (index + 1) + "번째");
      }
      this.cells.add(new AttemptCell(this, index + 1, submission, now));
    }
    this.correct = this.cells.stream().allMatch(AttemptCell::isCorrect);
  }

  /**
   * 개인 학습·복습(FREE) 풀이. 세션 연결 두 컬럼이 함께 필수이고 lecture_id는 NULL이다. 일반 학습이면
   * curriculum_stage_id가 존재하고 복습이면 NULL이다.
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
      LocalDateTime now) {
    if (learningSessionId == null || sessionItemNo == null) {
      throw new IllegalArgumentException("개인 학습 풀이는 세션과 배정 순번이 함께 필수입니다.");
    }
    if (sessionItemNo < 1) {
      throw new IllegalArgumentException("배정 순번은 1 이상이어야 합니다: " + sessionItemNo);
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
        now);
  }

  /**
   * 규칙 엔진이 독립 평가한 요소 결과를 붙인다. 같은 요소를 두 번 붙일 수 없다 (ERD 5.3절 UNIQUE(attempt_id, skill_id)).
   *
   * <p>evaluated_at은 원본 Attempt의 attempted_at과 같아야 하므로 인자로 받지 않는다.
   */
  public void addSkillResult(Long skillId, boolean correct, int errorCount) {
    boolean duplicated =
        skillResults.stream().anyMatch(result -> skillId.equals(result.getSkillId()));
    if (duplicated) {
      throw new IllegalArgumentException("같은 학습 요소의 평가가 이미 있습니다: " + skillId);
    }
    skillResults.add(new AttemptSkillResult(this, skillId, correct, errorCount, attemptedAt));
  }

  /**
   * 요소 평가 결과 집합이 문제의 연결 요소 집합과 일치하는지 확인한다 (ERD 5.3절).
   *
   * <p>problem_skills 조회가 필요하므로 기대 집합은 서비스가 넘긴다.
   */
  public boolean coversExactly(Set<Long> expectedSkillIds) {
    Set<Long> actual = new HashSet<>();
    for (AttemptSkillResult result : skillResults) {
      actual.add(result.getSkillId());
    }
    return actual.equals(expectedSkillIds);
  }

  public List<AttemptCell> getCells() {
    return Collections.unmodifiableList(cells);
  }

  public List<AttemptSkillResult> getSkillResults() {
    return Collections.unmodifiableList(skillResults);
  }

  public boolean isCorrect() {
    return correct;
  }
}
