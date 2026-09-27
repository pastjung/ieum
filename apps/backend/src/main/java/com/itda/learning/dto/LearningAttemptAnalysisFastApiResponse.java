package com.itda.learning.dto;

import java.util.List;
import java.util.Map;

public record LearningAttemptAnalysisFastApiResponse(
    Feedback feedback,
    List<SkillResult> skillResults,
    List<LearningSkillStatePayload> updatedSkillStates,
    /** 정답 칸마다 학습 요소 라벨("약자 바", "모음 ㅙ (1/2)"). 오답 화면 캡션용. 없으면 빈 목록 */
    List<CellSkill> cellSkills,
    /**
     * 오답일 때 "틀린 곳" 하나당 한 장. 칸을 맞춘 뒤의 오류라 칸을 더 넣거나 빼도 뒤 칸이 밀리지 않는다. 정답 칸이 없는 자리(없어야 할 칸을 넣음)는
     * expectedCellIndex·expectedPattern 이 null, label 이 "빈칸"이다. 없으면 빈 목록
     */
    List<FeedbackStep> feedbackSteps) {

  public LearningAttemptAnalysisFastApiResponse {
    cellSkills = cellSkills == null ? List.of() : List.copyOf(cellSkills);
    feedbackSteps = feedbackSteps == null ? List.of() : List.copyOf(feedbackSteps);
  }

  public LearningAttemptAnalysisFastApiResponse(
      Feedback feedback,
      List<SkillResult> skillResults,
      List<LearningSkillStatePayload> updatedSkillStates) {
    this(feedback, skillResults, updatedSkillStates, List.of(), List.of());
  }

  public LearningAttemptAnalysisFastApiResponse(
      Feedback feedback,
      List<SkillResult> skillResults,
      List<LearningSkillStatePayload> updatedSkillStates,
      List<CellSkill> cellSkills) {
    this(feedback, skillResults, updatedSkillStates, cellSkills, List.of());
  }

  /** 틀린 곳 하나. 패턴은 0~63, 점 번호는 1~6. tts 는 AI 채점기가 만든 설명 한 문장 */
  public record FeedbackStep(
      Integer expectedCellIndex,
      Integer expectedPattern,
      Integer inputPattern,
      List<Integer> missingDots,
      List<Integer> extraDots,
      String label,
      String errorType,
      String tts) {
    public FeedbackStep {
      missingDots = missingDots == null ? List.of() : List.copyOf(missingDots);
      extraDots = extraDots == null ? List.of() : List.copyOf(extraDots);
    }
  }

  /** cellIndex 는 1부터 (attempt_cells.cell_index 와 같다) */
  public record CellSkill(int cellIndex, Long learningSkillId, String label) {}

  public record Feedback(String tts, String detail) {}

  public record SkillResult(Long learningSkillId, boolean correct, Map<String, Long> errorCounts) {}
}
