package com.itda.learning.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record LearningSkillStatePayload(
    Long learningSkillId,
    int nAttempts,
    int nCorrect,
    LocalDateTime lastPracticedAt,
    Double masteryProb,
    Map<String, Long> errorCounts) {}
