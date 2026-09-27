package com.itda.lecture.dto;

import com.itda.problem.domain.Level;
import com.itda.problem.service.LinkedProblem;

/** 학습자에게 보이는 현재 문제. 제출 전에 정답 점형을 주지 않는다 (API 명세 8.2절). */
public record LearnerCurrentProblemResponse(
    String problemId,
    Level problemType,
    String targetText,
    String displayText,
    int cellCount,
    boolean submitted) {

  static LearnerCurrentProblemResponse from(LinkedProblem problem, boolean submitted) {
    return new LearnerCurrentProblemResponse(
        String.valueOf(problem.problemId()),
        problem.problemType(),
        problem.targetText(),
        problem.displayText(),
        problem.cellCount(),
        submitted);
  }
}
