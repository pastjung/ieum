package com.itda.learning.service;

import com.itda.learning.domain.LevelAttemptCount;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.problem.domain.Level;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문제 단계(Level)별 풀이 통계.
 *
 * <p>"내 실력" 화면이 {@code user_skill_memory}의 요소별 숙련도를 보여주고 있었다. 요소가 74개라 목록이 읽히지 않았고, 숙련도는 AI가 계산한
 * 확률이라 "내가 몇 개를 맞혔나"와는 다른 값이다. 이제 실제 풀이 기록({@code problem_attempts})에서 단계별 정답률을 낸다.
 */
@Service
@RequiredArgsConstructor
public class LevelStatsQueryService {

  private final ProblemAttemptRepository problemAttemptRepository;

  /**
   * 기록이 있는 단계만 돌려준다.
   *
   * <p>{@link StageStatsQueryService}는 기록 없는 단계까지 0으로 채우는데, 그쪽은 단계의 식별자와 이름을 서버만 알기 때문이다. Level은 값이
   * 고정된 enum이라 앱이 여섯 개 전부와 그 표시 순서·이름을 이미 들고 있다({@code
   * front/apps/mobile/src/services/levelLabels.ts}) — 서버가 빈 줄을 채워도 앱이 다시 채우는 일이 겹칠 뿐이다.
   *
   * <p>같은 이유로 순서를 약속하지 않는다. 학습 순서는 {@code curriculum_stages}가 정하고 앱이 그 순서를 들고 있다.
   */
  @Transactional(readOnly = true)
  public List<LevelStat> find(long userId) {
    return problemAttemptRepository.countByLevel(userId).stream()
        .map(LevelStatsQueryService::toStat)
        .toList();
  }

  private static LevelStat toStat(LevelAttemptCount count) {
    return new LevelStat(
        count.getLevel(),
        Math.toIntExact(count.getSolvedCount()),
        Math.toIntExact(count.getCorrectCount()));
  }

  public record LevelStat(Level level, int solvedCount, int correctCount) {}
}
