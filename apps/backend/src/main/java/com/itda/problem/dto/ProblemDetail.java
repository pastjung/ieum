package com.itda.problem.dto;

import java.util.List;

public record ProblemDetail(
    Long problemId,
    Long sequence,
    String targetText,
    List<Integer> expectedPatterns,
    Boolean completed) {}
