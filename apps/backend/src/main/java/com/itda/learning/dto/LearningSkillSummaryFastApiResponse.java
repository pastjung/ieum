package com.itda.learning.dto;

import java.util.List;

/** FastAPI {@code POST /skill-summary} 응답. 약한 것부터 정렬돼 온다 */
public record LearningSkillSummaryFastApiResponse(List<SkillMastery> skills) {

  public LearningSkillSummaryFastApiResponse {
    skills = skills == null ? List.of() : List.copyOf(skills);
  }

  public record SkillMastery(Long learningSkillId, Double masteryProb, String label) {}
}
