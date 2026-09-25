package com.itda.learning.dto;

import com.itda.learning.service.LearningAttemptService.AttemptResult;
import com.itda.learning.service.LearningAttemptService.CellResult;
import java.time.Instant;
import java.util.List;

public record LearningAttemptAnalysisResponse(
    Long problemSessionId,
    Long problemId,
    Long attemptId,
    boolean correct,
    Instant attemptedAt,
    List<CellAnalysisResponse> cells,
    FeedbackResponse feedback) {

  public static LearningAttemptAnalysisResponse from(AttemptResult result) {
    return new LearningAttemptAnalysisResponse(
        result.problemSessionId(),
        result.problemId(),
        result.attemptId(),
        result.correct(),
        result.attemptedAt(),
        result.cells().stream().map(CellAnalysisResponse::from).toList(),
        new FeedbackResponse(result.feedback().tts(), result.feedback().detail()));
  }

  public record FeedbackResponse(String tts, String detail) {}

  public record CellAnalysisResponse(
      int cellIndex,
      int expectedPattern,
      int inputPattern,
      int diffMask,
      int missingMask,
      int extraMask,
      List<Integer> missingDots,
      List<Integer> extraDots) {

    static CellAnalysisResponse from(CellResult result) {
      return new CellAnalysisResponse(
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
