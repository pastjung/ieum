package com.itda.learning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * attempt_skill_results — 한 시도에서 학습 요소를 어떻게 평가했는지 (ERD 5.3절).
 *
 * <p>ProblemAttempt 애그리거트에 속한다. 규칙 엔진이 연결된 각 요소를 독립 평가하며 문제 전체 오답을 모든 요소에 일괄 복사하지 않는다. 평가 자체는 규칙
 * 엔진(서비스)의 책임이고 이 엔티티는 결과의 불변식만 강제한다.
 *
 * <p>error_count는 진단 정보이고 BKT는 is_correct만 이진 관측으로 사용한다.
 */
@Entity
@Table(
    name = "attempt_skill_results",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_attempt_skill_results_attempt_skill",
            columnNames = {"attempt_id", "skill_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttemptSkillResult {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "attempt_id", nullable = false)
  private ProblemAttempt attempt;

  /** learning_skills.id. problem 컨텍스트를 참조하므로 Long으로 둔다. */
  @Column(name = "skill_id", nullable = false)
  private Long skillId;

  /** 요소별 최종 이진 관측. BKT가 사용하는 유일한 값이다. */
  @Column(name = "is_correct", nullable = false)
  private boolean correct;

  /** 규칙 엔진이 해당 요소에 명시적으로 귀속한 오류 수. BKT에 가중하지 않는다. */
  @Column(name = "error_count", nullable = false)
  private int errorCount;

  /** 원본 Attempt attempted_at과 같은 접수 시각 (ERD 5.3절). */
  @Column(name = "evaluated_at", nullable = false)
  private LocalDateTime evaluatedAt;

  AttemptSkillResult(
      ProblemAttempt attempt,
      Long skillId,
      boolean correct,
      int errorCount,
      LocalDateTime evaluatedAt) {
    if (attempt == null) {
      throw new IllegalArgumentException("풀이는 필수입니다.");
    }
    if (skillId == null) {
      throw new IllegalArgumentException("학습 요소는 필수입니다.");
    }
    // ERD 5.3절: is_correct=true이면 error_count=0, false이면 1 이상이어야 한다.
    if (correct && errorCount != 0) {
      throw new IllegalArgumentException("정답 요소의 오류 수는 0이어야 합니다: " + errorCount);
    }
    if (!correct && errorCount < 1) {
      throw new IllegalArgumentException("오답 요소의 오류 수는 1 이상이어야 합니다: " + errorCount);
    }
    this.attempt = attempt;
    this.skillId = skillId;
    this.correct = correct;
    this.errorCount = errorCount;
    this.evaluatedAt = evaluatedAt;
  }

  public boolean isCorrect() {
    return correct;
  }
}
