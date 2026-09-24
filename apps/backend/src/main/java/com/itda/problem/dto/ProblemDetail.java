package com.itda.problem.dto;

import java.util.List;

/**
 * 세션에 배정된 문항 하나.
 *
 * <p>{@code problemSessionId}는 이 문항 배정의 식별자다. 완료 제출이 이 값을 요구한다 — 같은 문제가 한 세션에 두 번 배정될 수 있어 {@code
 * problemId}로는 어느 배정인지 가릴 수 없다.
 */
public record ProblemDetail(
    Long problemSessionId,
    Long problemId,
    Long sequence,
    String targetText,
    List<Integer> expectedPatterns,
    Boolean completed) {}
