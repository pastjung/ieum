package com.itda.curriculum.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * curriculum_problems — 단계별 출제 후보 연결 (ERD 4.2절).
 *
 * <p>하나의 문제를 여러 단계에 연결할 수 있다. 주·보조 학습 요소 구분은 저장하지 않는다.
 *
 * <p>default_order_no가 NULL이 아니면 그 단계의 기본 문제다. SYSTEM Seed에서는 L1~L6 각 단계가 1~5의 서로 다른 기본 문제를 5개씩
 * 가진다.
 */
@Entity
@Table(
    name = "curriculum_problems",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_curriculum_problems_stage_default_order",
            columnNames = {"curriculum_stage_id", "default_order_no"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CurriculumProblem {

  @EmbeddedId private CurriculumProblemId id;

  /** NULL이 아니면 해당 단계의 기본 문제이고 1 이상이다. 교육·보충 순서를 함께 표현한다. */
  @Column(name = "default_order_no")
  private Integer defaultOrderNo;

  private CurriculumProblem(CurriculumProblemId id, Integer defaultOrderNo) {
    if (id == null) {
      throw new IllegalArgumentException("단계·문제 식별자는 필수입니다.");
    }
    if (defaultOrderNo != null && defaultOrderNo < 1) {
      throw new IllegalArgumentException("기본 문제 순번은 1 이상이어야 합니다: " + defaultOrderNo);
    }
    this.id = id;
    this.defaultOrderNo = defaultOrderNo;
  }

  /** 단계에 출제 후보로만 연결한다 (기본 문제 아님). */
  public static CurriculumProblem link(Long curriculumStageId, Long problemId) {
    return new CurriculumProblem(CurriculumProblemId.of(curriculumStageId, problemId), null);
  }

  /** 단계의 기본 문제로 연결한다. */
  public static CurriculumProblem linkAsDefault(
      Long curriculumStageId, Long problemId, int defaultOrderNo) {
    return new CurriculumProblem(
        CurriculumProblemId.of(curriculumStageId, problemId), defaultOrderNo);
  }

  public boolean isDefaultProblem() {
    return defaultOrderNo != null;
  }
}
