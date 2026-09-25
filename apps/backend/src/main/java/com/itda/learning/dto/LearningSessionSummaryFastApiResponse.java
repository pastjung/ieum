package com.itda.learning.dto;

import java.util.List;
import java.util.Map;

public record LearningSessionSummaryFastApiResponse(
    String summary, String ttsText, Map<String, Long> errorTypeCounts, List<WeakSkill> weakSkills) {

  public record WeakSkill(
      Long learningSkillId,
      Double masteryProb,
      String label,
      String dominantErrorType,
      String explanation) {}
}
