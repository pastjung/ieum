package com.itda.problem.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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

/**
 * problem_skills — 문제에 포함된 학습 요소 연결 (ERD 4.6절).
 *
 * <p>하나의 문제에 여러 요소를 연결하고, 같은 요소를 여러 문제에서 참조할 수 있다. 주·보조 구분은 현재 설계와 확장 방향 모두에서 사용하지 않는다.
 *
 * <p>ERD는 (problem_id, skill_id) 복합 PK로 정의하지만, 다른 엔티티와 매핑 방식을 맞추기 위해 대리 키 id를 PK로 두고 두 FK를 단방향 다대일로
 * 참조한다. ERD가 요구하는 "동일 문제·요소 연결 중복 방지"는 (problem_id, skill_id) 유니크 제약으로 유지한다.
 *
 * <p>MVP에 저장·출제하는 모든 문제는 최소 한 요소와 연결한다. 관계 자체가 추천 순위·가중치를 저장하지는 않는다.
 */
@Entity
@Table(
    name = "problem_skills",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_problem_skills_problem_skill",
            columnNames = {"problem_id", "skill_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemSkill {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "problem_id", nullable = false)
  private Problem problem;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "skill_id", nullable = false)
  private LearningSkill skill;

  public ProblemSkill(Problem problem, LearningSkill skill) {
    this.problem = problem;
    this.skill = skill;
  }
}
