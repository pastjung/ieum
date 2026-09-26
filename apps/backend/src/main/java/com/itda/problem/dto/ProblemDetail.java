package com.itda.problem.dto;

import java.util.List;

/**
 * 세션에 배정된 문항 하나.
 *
 * <p>{@code problemSessionId}는 이 문항 배정의 식별자다. 완료 제출이 이 값을 요구한다 — 앱은 {@code problemId}로부터 이 값을 유도할 수
 * 없다. 그 대응을 아는 것은 서버뿐이므로 응답에 실어 보내야 한다.
 */
public record ProblemDetail(
    Long problemSessionId,
    Long problemId,
    Long sequence,
    String targetText,
    List<Integer> expectedPatterns,
    Boolean completed) {}
