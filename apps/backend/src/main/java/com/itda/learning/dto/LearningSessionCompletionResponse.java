package com.itda.learning.dto;

import com.itda.learning.service.LearningSessionCompletionService.CellResult;
import com.itda.learning.service.LearningSessionCompletionService.CompletionResult;
import com.itda.learning.service.LearningSessionCompletionService.ProblemResult;
import java.time.Instant;
import java.util.List;

public record LearningSessionCompletionResponse(
    Long sessionId,
    Instant completedAt,
    int totalProblemCount,
    int correctProblemCount,
    List<ProblemCompletionResponse> problemResults) {

  public static LearningSessionCompletionResponse from(CompletionResult result) {
    return new LearningSessionCompletionResponse(
        result.sessionId(),
        result.completedAt(),
        result.totalProblemCount(),
        result.correctProblemCount(),
        result.problemResults().stream().map(ProblemCompletionResponse::from).toList());
  }

  public record ProblemCompletionResponse(
      Long problemSessionId,
      Long problemId,
      Long attemptId,
      boolean correct,
      List<CellCompletionResponse> cells) {

    static ProblemCompletionResponse from(ProblemResult result) {
      return new ProblemCompletionResponse(
          result.problemSessionId(),
          result.problemId(),
          result.attemptId(),
          result.correct(),
          result.cells().stream().map(CellCompletionResponse::from).toList());
    }
  }

  public record CellCompletionResponse(
      int cellIndex,
      int expectedPattern,
      int inputPattern,
      int diffMask,
      int missingMask,
      int extraMask,
      List<Integer> missingDots,
      List<Integer> extraDots) {

    static CellCompletionResponse from(CellResult result) {
      return new CellCompletionResponse(
          result.cellIndex(),
          result.expectedPattern(),
          result.inputPattern(),
          result.diffMask(),
          result.missingMask(),
          result.extraMask(),
          result.missingDots(),
          result.extraDots());
    }
  }
}
