package com.itda.learning.dto;

import java.util.List;

public record LearningSessionSummaryFastApiRequest(
    List<LearningAnalysisAttemptPayload> attempts,
    List<LearningSkillStatePayload> skillStates) {}
