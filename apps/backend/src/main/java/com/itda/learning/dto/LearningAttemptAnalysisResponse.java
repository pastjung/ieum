package com.itda.learning.dto;

import com.itda.learning.service.LearningAttemptService.AttemptResult;
import com.itda.learning.service.LearningAttemptService.CellResult;
import com.itda.learning.service.LearningAttemptService.ViolatedSkill;
import java.time.Instant;
import java.util.List;

public record LearningAttemptAnalysisResponse(
    Long problemSessionId,
    Long problemId,
    Long attemptId,
    boolean correct,
    Instant attemptedAt,
    List<CellAnalysisResponse> cells,
    FeedbackResponse feedback,
    List<ViolatedSkillResponse> violatedSkills,
    /**
     * 오답의 "틀린 곳" 하나당 한 장 (AI 가 칸을 맞춘 뒤 고른 것). 칸을 더 넣거나 빼도 뒤 칸이 밀리지 않는다. 정답 칸이 없는 자리는
     * expectedPattern 이 null, label 이 "빈칸". 정답이면 빈 목록
     */
    List<FeedbackStepResponse> feedbackSteps) {

  public static LearningAttemptAnalysisResponse from(AttemptResult result) {
    return new LearningAttemptAnalysisResponse(
        result.problemSessionId(),
        result.problemId(),
        result.attemptId(),
        result.correct(),
        result.attemptedAt(),
        result.cells().stream().map(CellAnalysisResponse::from).toList(),
        new FeedbackResponse(result.feedback().tts(), result.feedback().detail()),
        result.violatedSkills().stream().map(ViolatedSkillResponse::from).toList(),
        result.feedbackSteps().stream().map(FeedbackStepResponse::from).toList());
  }

  public record FeedbackStepResponse(
      Integer expectedCellIndex,
      Integer expectedPattern,
      Integer inputPattern,
      List<Integer> missingDots,
      List<Integer> extraDots,
      String label,
      String errorType,
      String tts) {
    static FeedbackStepResponse from(LearningAttemptAnalysisFastApiResponse.FeedbackStep step) {
      return new FeedbackStepResponse(
          step.expectedCellIndex(),
          step.expectedPattern(),
          step.inputPattern(),
          step.missingDots(),
          step.extraDots(),
          step.label(),
          step.errorType(),
          step.tts());
    }
  }

  public record FeedbackResponse(String tts, String detail) {}

  /** 이번 풀이에서 어긴 점자 규칙. 학습자 화면이 "왜 틀렸는지"로 읽는다 */
  public record ViolatedSkillResponse(Long id, String name, String description) {

    static ViolatedSkillResponse from(ViolatedSkill skill) {
      return new ViolatedSkillResponse(skill.id(), skill.name(), skill.description());
    }
  }

  public record CellAnalysisResponse(
      int cellIndex,
      int expectedPattern,
      int inputPattern,
      int diffMask,
      int missingMask,
      int extraMask,
      List<Integer> missingDots,
      List<Integer> extraDots,
      /** 이 정답 칸의 학습 요소 id. 없으면 null */
      Long learningSkillId,
      /** 화면 캡션용 요소 이름("약자 바", "모음 ㅙ (1/2)"). 없으면 null */
      String label) {

    static CellAnalysisResponse from(CellResult result) {
      return new CellAnalysisResponse(
          result.cellIndex(),
          result.expectedPattern(),
          result.inputPattern(),
          result.diffMask(),
          result.missingMask(),
          result.extraMask(),
          result.missingDots(),
          result.extraDots(),
          result.learningSkillId(),
          result.label());
    }
  }
}
