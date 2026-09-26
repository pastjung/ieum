package com.itda.learning.dto;

import java.util.List;

public record LearningAttemptAnalysisFastApiRequest(
    LearningAnalysisAttemptPayload attempt, List<LearningSkillStatePayload> skillStates) {}
