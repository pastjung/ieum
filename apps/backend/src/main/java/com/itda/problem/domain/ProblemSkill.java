package com.itda.problem.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * problem_skills — 문제에 포함된 학습 요소 연결 (ERD 4.6절).
 *
 * <p>하나의 문제에 여러 요소를 연결하고, 같은 요소를 여러 문제에서 참조할 수 있다. 주·보조 구분은 현재 설계와 확장 방향 모두에서 사용하지 않는다.
 *
 * <p>MVP에 저장·출제하는 모든 문제는 최소 한 요소와 연결한다. 관계 자체가 추천 순위·가중치를 저장하지는 않는다.
 */
@Entity
@Table(name = "problem_skills")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemSkill {

  @EmbeddedId private ProblemSkillId id;

  private ProblemSkill(ProblemSkillId id) {
    if (id == null) {
      throw new IllegalArgumentException("문제·요소 식별자는 필수입니다.");
    }
    this.id = id;
  }

  public static ProblemSkill link(Long problemId, Long skillId) {
    return new ProblemSkill(ProblemSkillId.of(problemId, skillId));
  }
}
