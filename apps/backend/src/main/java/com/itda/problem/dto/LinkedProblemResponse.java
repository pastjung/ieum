package com.itda.problem.dto;

import com.itda.problem.domain.Level;
import com.itda.problem.service.LinkedProblem;
import java.util.List;

/** API 명세 8.2.1절 LinkedProblem. 문제 등록 응답과 강사 Snapshot이 같이 쓴다. problemId는 10진수 문자열이다. */
public record LinkedProblemResponse(
    String problemId,
    String targetText,
    String displayText,
    Level problemType,
    int cellCount,
    List<Integer> expectedPattern) {

  public static LinkedProblemResponse from(LinkedProblem problem) {
    return new LinkedProblemResponse(
        String.valueOf(problem.problemId()),
        problem.targetText(),
        problem.displayText(),
        problem.problemType(),
        problem.cellCount(),
        problem.expectedPattern());
  }
}
