package com.itda.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.itda.curriculum.domain.CurriculumStage;
import com.itda.curriculum.domain.CurriculumStageRepository;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.learning.domain.StageAttemptCount;
import com.itda.problem.domain.Level;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 테마(커리큘럼 단계)별 풀이 통계.
 *
 * <p>테마 선택 화면이 {@code mockData.learningThemes}의 고정 숫자를 보여주고 있었다 — "자음 48문제 중 43개 맞힘 90%" 같은 값이
 * 전부 지어낸 것이었고, 학습자는 이것을 자기 기록으로 읽었다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StageStatsQueryServiceTest {

  private static final long USER_ID = 1L;

  @Mock private ProblemAttemptRepository problemAttemptRepository;
  @Mock private CurriculumStageRepository curriculumStageRepository;

  private StageStatsQueryService service;

  @BeforeEach
  void setUp() {
    service = new StageStatsQueryService(problemAttemptRepository, curriculumStageRepository);
    // 목록을 먼저 만든다. given(...) 인자 안에서 mock 을 스텁하면 Mockito 가 중첩 스텁으로 본다
    List<CurriculumStage> stages = stages();
    given(curriculumStageRepository.findAll()).willReturn(stages);
  }

  /** curriculum_stages 의 실제 순서: 자음·모음·약자·단어·약어·문장 */
  private List<CurriculumStage> stages() {
    Object[][] rows = {
      {1L, "자음", Level.CONSONANT},
      {2L, "모음", Level.VOWEL},
      {3L, "약자", Level.CONTRACTION},
    };
    List<CurriculumStage> out = new ArrayList<>();
    for (int i = 0; i < rows.length; i++) {
      CurriculumStage stage = mock(CurriculumStage.class);
      given(stage.getId()).willReturn((Long) rows[i][0]);
      given(stage.getName()).willReturn((String) rows[i][1]);
      given(stage.getLevel()).willReturn((Level) rows[i][2]);
      given(stage.getOrderNo()).willReturn(i + 1);
      out.add(stage);
    }
    return out;
  }

  private StageAttemptCount count(long stageId, long solved, long correct) {
    StageAttemptCount row = mock(StageAttemptCount.class);
    given(row.getCurriculumStageId()).willReturn(stageId);
    given(row.getSolvedCount()).willReturn(solved);
    given(row.getCorrectCount()).willReturn(correct);
    return row;
  }

  @Test
  void 단계마다_푼_수와_맞힌_수를_돌려준다() {
    List<StageAttemptCount> counts = List.of(count(1L, 48L, 43L));
    given(problemAttemptRepository.countByStage(USER_ID)).willReturn(counts);

    StageStatsQueryService.StageStat stat = service.find(USER_ID).getFirst();

    assertThat(stat.curriculumStageId()).isEqualTo(1L);
    assertThat(stat.name()).isEqualTo("자음");
    assertThat(stat.solvedCount()).isEqualTo(48);
    assertThat(stat.correctCount()).isEqualTo(43);
  }

  @Test
  void 기록이_없는_단계도_0으로_내보낸다() {
    // 빼 버리면 화면이 그 테마를 아예 못 그린다 — 아직 안 푼 단계도 고를 수 있어야 한다
    List<StageAttemptCount> counts = List.of(count(1L, 48L, 43L));
    given(problemAttemptRepository.countByStage(USER_ID)).willReturn(counts);

    List<StageStatsQueryService.StageStat> stats = service.find(USER_ID);

    assertThat(stats).hasSize(3);
    assertThat(stats.get(1).solvedCount()).isZero();
    assertThat(stats.get(1).correctCount()).isZero();
  }

  @Test
  void 커리큘럼_순서를_지킨다() {
    // 화면이 쉬운 단계부터 늘어놓는다. 집계 결과의 순서는 DB가 정할 일이 아니다
    List<StageAttemptCount> counts = List.of(count(3L, 5L, 2L), count(1L, 48L, 43L));
    given(problemAttemptRepository.countByStage(USER_ID)).willReturn(counts);

    assertThat(service.find(USER_ID))
        .extracting(StageStatsQueryService.StageStat::name)
        .containsExactly("자음", "모음", "약자");
  }

  @Test
  void 기록이_하나도_없으면_모두_0이다() {
    given(problemAttemptRepository.countByStage(USER_ID)).willReturn(List.of());

    assertThat(service.find(USER_ID))
        .hasSize(3)
        .allSatisfy(stat -> assertThat(stat.solvedCount()).isZero());
  }

  @Test
  void 단계가_사라진_집계는_버린다() {
    // curriculum_stages 에 없는 stage_id 가 오래된 풀이에 남아 있을 수 있다.
    // 이름을 알 수 없는 줄을 화면에 그릴 수는 없다
    List<StageAttemptCount> counts = List.of(count(99L, 10L, 9L));
    given(problemAttemptRepository.countByStage(USER_ID)).willReturn(counts);

    assertThat(service.find(USER_ID))
        .hasSize(3)
        .allSatisfy(stat -> assertThat(stat.solvedCount()).isZero());
  }
}
