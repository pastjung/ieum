package com.itda.learning.dto;

import com.itda.learning.service.StageStatsQueryService.StageStat;
import com.itda.problem.domain.Level;
import java.util.List;

/**
 * 테마(커리큘럼 단계)별 풀이 통계. 커리큘럼 순서로 모든 단계가 들어 있다.
 *
 * <p>기록이 없는 단계도 0으로 나간다 — 빼면 화면이 그 테마를 그리지 못해 아직 안 푼 단계를 고를 수 없다.
 */
public record StageStatsResponse(List<StageStatResponse> stages) {

  public static StageStatsResponse from(List<StageStat> stats) {
    return new StageStatsResponse(stats.stream().map(StageStatResponse::from).toList());
  }

  public record StageStatResponse(
      Long curriculumStageId, String name, Level level, int solvedCount, int correctCount) {

    static StageStatResponse from(StageStat stat) {
      return new StageStatResponse(
          stat.curriculumStageId(),
          stat.name(),
          stat.level(),
          stat.solvedCount(),
          stat.correctCount());
    }
  }
}
