package com.itda.learning.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record LearningSkillStatePayload(
    Long learningSkillId,
    int nAttempts,
    int nCorrect,
    LocalDateTime lastPracticedAt,
    Double masteryProb,
    Map<String, Long> errorCounts,
    /** 최근 5회 정오('0'=틀림 '1'=맞음, 오래된 것부터). AI 가 만들고 백엔드는 저장·전달만 한다. 없으면 빈 문자열 */
    String recentResults) {

  public LearningSkillStatePayload {
    recentResults = recentResults == null ? "" : recentResults;
  }

  /** recentResults 가 아직 없는 호출처(요약 조회·테스트)를 위한 생성자 */
  public LearningSkillStatePayload(
      Long learningSkillId,
      int nAttempts,
      int nCorrect,
      LocalDateTime lastPracticedAt,
      Double masteryProb,
      Map<String, Long> errorCounts) {
    this(learningSkillId, nAttempts, nCorrect, lastPracticedAt, masteryProb, errorCounts, "");
  }
}
