package com.itda.learning.dto;

import com.itda.learning.service.LevelStatsQueryService.LevelStat;
import com.itda.problem.domain.Level;
import java.util.List;

/**
 * 문제 단계(Level)별 풀이 통계. "내 실력" 화면이 단계마다 정답률을 보여준다.
 *
 * <p><b>기록이 있는 단계만 들어 있다.</b> {@link StageStatsResponse}는 기록 없는 단계까지 0으로 채워 보내는데, 그쪽은 단계의 식별자와 이름을
 * 서버만 알기 때문이다. Level은 값이 고정된 enum이라 앱이 여섯 개 전부와 그 표시 순서·이름을 이미 알고 있다({@code
 * front/apps/mobile/src/services/levelLabels.ts}) — 서버가 빈 줄을 채워 보내도 앱이 다시 채우는 일이 겹칠 뿐이다.
 *
 * <p>같은 이유로 <b>순서를 약속하지 않는다.</b> 학습 순서(자음·모음·약자·단어·약어·문장)는 {@code curriculum_stages}가 정하고 앱이 그 순서를
 * 들고 있다. 집계 결과의 순서는 DB가 정할 일이 아니다.
 */
public record LevelStatsResponse(List<LevelStatResponse> levels) {

  public static LevelStatsResponse from(List<LevelStat> stats) {
    return new LevelStatsResponse(stats.stream().map(LevelStatResponse::from).toList());
  }

  public record LevelStatResponse(Level level, int solvedCount, int correctCount) {

    static LevelStatResponse from(LevelStat stat) {
      return new LevelStatResponse(stat.level(), stat.solvedCount(), stat.correctCount());
    }
  }
}
