package com.itda.learning.dto;

import java.util.List;
import java.util.Map;

public record LearningAttemptAnalysisFastApiResponse(
    Feedback feedback,
    List<SkillResult> skillResults,
    List<LearningSkillStatePayload> updatedSkillStates) {

  public record Feedback(String tts, String detail) {}

  public record SkillResult(Long learningSkillId, boolean correct, Map<String, Long> errorCounts) {}
}
