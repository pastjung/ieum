package com.itda.learning.dto;

import com.itda.learning.service.SkillSummaryQueryService.SkillMasteryView;
import com.itda.learning.service.SkillSummaryQueryService.SkillSummary;
import java.util.List;

/**
 * "내 실력" 화면과 홈 복습 카드가 읽는 숙련 상태.
 *
 * <p>{@code skills}는 약한 것부터 정렬돼 있다 — 두 화면이 서로 다른 순서를 쓰지 않도록 FastAPI가 정한 순서를 그대로 지킨다.
 * FastAPI가 응답하지 않으면 비어 있고, 그때도 풀이 통계는 그대로 나간다.
 */
public record SkillSummaryResponse(
    int totalAttemptCount, int correctAttemptCount, List<SkillMasteryResponse> skills) {

  public static SkillSummaryResponse from(SkillSummary summary) {
    return new SkillSummaryResponse(
        summary.totalAttemptCount(),
        summary.correctAttemptCount(),
        summary.skills().stream().map(SkillMasteryResponse::from).toList());
  }

  /**
   * @param masteryProb 0~1 숙련 추정치
   * @param label "익숙해요" | "배우는 중" | "더 연습해요" — 기준은 FastAPI에만 있다
   */
  public record SkillMasteryResponse(
      Long learningSkillId, String name, Double masteryProb, String label) {

    static SkillMasteryResponse from(SkillMasteryView view) {
      return new SkillMasteryResponse(
          view.learningSkillId(), view.name(), view.masteryProb(), view.label());
    }
  }
}
