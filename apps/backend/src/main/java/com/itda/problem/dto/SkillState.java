package com.itda.problem.dto;

import java.time.LocalDateTime;

public record SkillState(
    Long learningSkillId,
    Integer nAttempts,
    Integer nCorrect,
    LocalDateTime lastPracticedAt,
    Double masteryProb,
    ErrorCount errorCounts) {}
