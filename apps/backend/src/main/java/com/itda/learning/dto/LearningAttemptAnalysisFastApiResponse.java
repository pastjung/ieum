package com.itda.learning.dto;

import java.util.List;
import java.util.Map;

public record LearningAttemptAnalysisFastApiResponse(
    Feedback feedback,
    List<SkillResult> skillResults,
    List<LearningSkillStatePayload> updatedSkillStates,
    /** 정답 칸마다 학습 요소 라벨("약자 바", "모음 ㅙ (1/2)"). 오답 화면 캡션용. 없으면 빈 목록 */
    List<CellSkill> cellSkills) {

  public LearningAttemptAnalysisFastApiResponse {
    cellSkills = cellSkills == null ? List.of() : List.copyOf(cellSkills);
  }

  public LearningAttemptAnalysisFastApiResponse(
      Feedback feedback,
      List<SkillResult> skillResults,
      List<LearningSkillStatePayload> updatedSkillStates) {
    this(feedback, skillResults, updatedSkillStates, List.of());
  }

  /** cellIndex 는 1부터 (attempt_cells.cell_index 와 같다) */
  public record CellSkill(int cellIndex, Long learningSkillId, String label) {}

  public record Feedback(String tts, String detail) {}

  public record SkillResult(Long learningSkillId, boolean correct, Map<String, Long> errorCounts) {}
}
