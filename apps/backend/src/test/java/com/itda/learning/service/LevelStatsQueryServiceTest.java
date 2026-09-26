package com.itda.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.itda.learning.domain.LevelAttemptCount;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.problem.domain.Level;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 문제 단계(Level)별 풀이 통계.
 *
 * <p>"내 실력" 화면이 {@code user_skill_memory}의 요소별 숙련도를 보여주고 있었다. 요소는 74개라 목록이 읽히지 않고, 숙련도는 AI가 계산한
 * 확률이라 "내가 몇 개를 맞혔나"와 다른 값이다. 이제 실제 풀이 기록에서 단계별 정답률을 낸다.
 */
@ExtendWith(MockitoExtension.class)
class LevelStatsQueryServiceTest {

  private static final long USER_ID = 1L;

  @Mock private ProblemAttemptRepository problemAttemptRepository;

  private LevelStatsQueryService service;

  @BeforeEach
  void setUp() {
    service = new LevelStatsQueryService(problemAttemptRepository);
  }

  /**
   * 집계 행을 만들어 스텁까지 끝낸다.
   *
   * <p>{@code given(...)} 인자 안에서 이 메서드를 부르면 Mockito가 중첩 스텁으로 보고 {@code UnfinishedStubbingException}을
   * 낸다. 목록을 먼저 만든 뒤 {@link #stub}으로 넘긴다 ({@code StageStatsQueryServiceTest}와 같은 규칙).
   */
  private LevelAttemptCount count(Level level, long solved, long correct) {
    LevelAttemptCount row = mock(LevelAttemptCount.class);
    given(row.getLevel()).willReturn(level);
    given(row.getSolvedCount()).willReturn(solved);
    given(row.getCorrectCount()).willReturn(correct);
    return row;
  }

  private void stub(List<LevelAttemptCount> counts) {
    given(problemAttemptRepository.countByLevel(USER_ID)).willReturn(counts);
  }

  @Test
  void 단계마다_푼_수와_맞힌_수를_돌려준다() {
    stub(List.of(count(Level.CONSONANT, 48L, 43L)));

    LevelStatsQueryService.LevelStat stat = service.find(USER_ID).getFirst();

    assertThat(stat.level()).isEqualTo(Level.CONSONANT);
    assertThat(stat.solvedCount()).isEqualTo(48);
    assertThat(stat.correctCount()).isEqualTo(43);
  }

  @Test
  void 집계된_단계를_모두_옮긴다() {
    stub(List.of(count(Level.CONSONANT, 48L, 43L), count(Level.WORD, 5L, 2L)));

    assertThat(service.find(USER_ID))
        .extracting(LevelStatsQueryService.LevelStat::level)
        .containsExactlyInAnyOrder(Level.CONSONANT, Level.WORD);
  }

  @Test
  void 기록이_없는_단계는_빼고_보낸다() {
    // 빈 단계를 서버가 채우지 않는다 — Level은 값이 고정된 enum이라 앱이 여섯 개 전부와
    // 그 표시 순서·이름을 이미 들고 있다(levelLabels.ts). 서버가 채우면 두 곳이 겹친다
    stub(List.of(count(Level.CONSONANT, 48L, 43L)));

    assertThat(service.find(USER_ID)).hasSize(1);
  }

  @Test
  void 풀이가_하나도_없으면_빈_목록이다() {
    stub(List.of());

    assertThat(service.find(USER_ID)).isEmpty();
  }

  @Test
  void 한_번도_맞히지_못한_단계도_푼_수는_센다() {
    // 0을 "기록 없음"으로 뭉개면 학습자는 그 단계를 아예 안 푼 것으로 읽는다.
    // 푼 수와 맞힌 수는 서로 다른 사실이다
    stub(List.of(count(Level.SENTENCE, 7L, 0L)));

    LevelStatsQueryService.LevelStat stat = service.find(USER_ID).getFirst();

    assertThat(stat.solvedCount()).isEqualTo(7);
    assertThat(stat.correctCount()).isZero();
  }
}
