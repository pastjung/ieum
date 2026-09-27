package com.itda.learning.dto;

import java.util.List;
import java.util.Map;

/**
 * FastAPI {@code POST /skill-states} 요청.
 *
 * <p>글자를 보내지 않는다. 낱자 문항('ㄱ'·'ㅙ')은 점역기가 분해하지 못해({@code non-Hangul}) 백엔드가 직접 채점하는데, 숙련도는 FastAPI의 BKT
 * 추정기에만 있어 갱신할 방법이 없었다. 그 탓에 자음·모음은 아무리 틀려도 약한 요소로 뽑히지 않아 복습에 나오지 않았다.
 *
 * @param skillStates 지금까지의 상태
 * @param results 이번 풀이의 요소별 정오
 */
public record LearningSkillStatesFastApiRequest(
    List<LearningSkillStatePayload> skillStates, List<SkillOutcome> results) {

  public LearningSkillStatesFastApiRequest {
    skillStates = List.copyOf(skillStates);
    results = List.copyOf(results);
  }

  public record SkillOutcome(Long learningSkillId, boolean correct, Map<String, Long> errorCounts) {
    public SkillOutcome {
      errorCounts = Map.copyOf(errorCounts);
    }
  }
}
