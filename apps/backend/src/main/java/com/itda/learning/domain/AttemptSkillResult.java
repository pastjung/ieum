package com.itda.learning.domain;

import com.itda.problem.domain.LearningSkill;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

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
  @JoinColumn(
      name = "attempt_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_attempt_skill_results_attempt"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private ProblemAttempt attempt;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "skill_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_attempt_skill_results_skill"))
  private LearningSkill skill;

  @Column(name = "is_correct", nullable = false)
  private boolean correct;

  @Column(name = "error_count", nullable = false)
  private int errorCount;

  public AttemptSkillResult(
      ProblemAttempt attempt, LearningSkill skill, boolean correct, int errorCount) {
    if (attempt == null) {
      throw new IllegalArgumentException("문제 풀이 시도는 필수입니다.");
    }
    if (skill == null) {
      throw new IllegalArgumentException("학습 요소는 필수입니다.");
    }
    if ((correct && errorCount != 0) || (!correct && errorCount <= 0)) {
      throw new IllegalArgumentException("정오답 여부와 오류 횟수가 일치하지 않습니다.");
    }
    this.attempt = attempt;
    this.skill = skill;
    this.correct = correct;
    this.errorCount = errorCount;
  }

  public boolean isCorrect() {
    return correct;
  }
}
