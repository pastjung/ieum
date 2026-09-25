package com.itda.learning.dto;

import java.util.List;

public record LearningAnalysisAttemptPayload(
    String targetText, List<Integer> expectedPatterns, List<Integer> inputPatterns) {}
