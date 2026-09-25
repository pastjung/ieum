package com.itda.learning.dto;

import java.util.List;

/**
 * FastAPI {@code POST /skill-summary} 요청. 상태를 바꾸지 않는 읽기다.
 *
 * <p>숙련 라벨의 기준({@code label_of})은 FastAPI에만 있다. 백엔드가 문턱값을 베끼면 두 곳이 갈린다.
 */
public record LearningSkillSummaryFastApiRequest(List<LearningSkillStatePayload> skillStates) {

  public LearningSkillSummaryFastApiRequest {
    skillStates = List.copyOf(skillStates);
  }
}
