package com.itda.learning.dto;

import java.util.List;

/**
 * FastAPI {@code POST /skill-states} 응답.
 *
 * <p>{@code attempt-analyze}의 {@code updatedSkillStates}와 같은 모양이라 저장 경로({@code
 * persistUpdatedSkillStates})를 그대로 쓴다.
 */
public record LearningSkillStatesFastApiResponse(List<LearningSkillStatePayload> updatedSkillStates) {

  public LearningSkillStatesFastApiResponse {
    updatedSkillStates = updatedSkillStates == null ? List.of() : List.copyOf(updatedSkillStates);
  }
}
