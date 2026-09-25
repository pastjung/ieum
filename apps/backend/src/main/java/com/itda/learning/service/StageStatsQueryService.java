package com.itda.learning.service;

import com.itda.curriculum.domain.CurriculumStage;
import com.itda.curriculum.domain.CurriculumStageRepository;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.learning.domain.StageAttemptCount;
import com.itda.problem.domain.Level;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테마(커리큘럼 단계)별 풀이 통계.
 *
 * <p>테마 선택 화면이 {@code mockData.learningThemes}의 고정 숫자를 보여주고 있었다 — "자음 48문제 중 43개 맞힘 90%" 같은 값이 전부
 * 지어낸 것이었고, 학습자는 이것을 자기 기록으로 읽었다.
 */
@Service
@RequiredArgsConstructor
public class StageStatsQueryService {

  private final ProblemAttemptRepository problemAttemptRepository;
  private final CurriculumStageRepository curriculumStageRepository;

  /**
   * 모든 단계를 커리큘럼 순서로 돌려준다.
   *
   * <p>기록이 없는 단계도 0으로 내보낸다 — 빼 버리면 화면이 그 테마를 아예 그리지 못해 아직 안 푼 단계를 고를 수 없다.
   *
   * <p>순서는 {@code curriculum_stages.order_no}가 정한다. 집계 결과의 순서는 DB가 정할 일이 아니고, {@code Level} enum의
   * 선언 순서도 학습 순서와 다르다.
   */
  @Transactional(readOnly = true)
  public List<StageStat> find(long userId) {
    // curriculum_stages 에 없는 stage_id 가 오래된 풀이에 남아 있을 수 있다. 이름을 알 수
    // 없는 줄은 화면에 그릴 수 없으므로 아래 join 에서 자연히 걸러진다
    Map<Long, StageAttemptCount> countsByStageId =
        problemAttemptRepository.countByStage(userId).stream()
            .collect(
                Collectors.toMap(
                    StageAttemptCount::getCurriculumStageId, Function.identity(), (a, b) -> a));

    return curriculumStageRepository.findAll().stream()
        .sorted(Comparator.comparingInt(CurriculumStage::getOrderNo))
        .map(
            stage -> {
              StageAttemptCount count = countsByStageId.get(stage.getId());
              return new StageStat(
                  stage.getId(),
                  stage.getName(),
                  stage.getLevel(),
                  count == null ? 0 : Math.toIntExact(count.getSolvedCount()),
                  count == null ? 0 : Math.toIntExact(count.getCorrectCount()));
            })
        .toList();
  }

  public record StageStat(
      Long curriculumStageId, String name, Level level, int solvedCount, int correctCount) {}
}
