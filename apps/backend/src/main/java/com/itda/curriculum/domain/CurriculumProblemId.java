package com.itda.curriculum.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** curriculum_problems의 복합 PK (curriculum_stage_id, problem_id) — ERD 4.2절. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CurriculumProblemId implements Serializable {

  @Column(name = "curriculum_stage_id", nullable = false)
  private Long curriculumStageId;

  @Column(name = "problem_id", nullable = false)
  private Long problemId;

  private CurriculumProblemId(Long curriculumStageId, Long problemId) {
    this.curriculumStageId = curriculumStageId;
    this.problemId = problemId;
  }

  public static CurriculumProblemId of(Long curriculumStageId, Long problemId) {
    if (curriculumStageId == null || problemId == null) {
      throw new IllegalArgumentException("단계와 문제는 모두 필수입니다.");
    }
    return new CurriculumProblemId(curriculumStageId, problemId);
  }
}
