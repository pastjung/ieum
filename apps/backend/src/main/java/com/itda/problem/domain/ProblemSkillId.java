package com.itda.problem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** problem_skills의 복합 PK (problem_id, skill_id) — ERD 4.6절. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemSkillId implements Serializable {

  @Column(name = "problem_id", nullable = false)
  private Long problemId;

  @Column(name = "skill_id", nullable = false)
  private Long skillId;

  private ProblemSkillId(Long problemId, Long skillId) {
    this.problemId = problemId;
    this.skillId = skillId;
  }

  public static ProblemSkillId of(Long problemId, Long skillId) {
    if (problemId == null || skillId == null) {
      throw new IllegalArgumentException("문제와 학습 요소는 모두 필수입니다.");
    }
    return new ProblemSkillId(problemId, skillId);
  }
}
