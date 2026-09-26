package com.itda.learning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

/**
 * 문제 단계(Level)별 풀이 집계 쿼리.
 *
 * <p>{@code problem_attempts}는 문제를 연관관계가 아니라 {@code problemId} 컬럼으로 들고 있어서 단계를 알려면 {@code problems}와
 * 엔티티 조인을 해야 한다. 조인이 깨지면 애플리케이션이 뜰 때가 아니라 화면이 통계를 부를 때 터지므로 실제 DB로 확인한다.
 */
@DataJpaTest
class ProblemAttemptRepositoryTest {

  private static final long USER_ID = 7L;
  private static final LocalDateTime ATTEMPTED_AT = LocalDateTime.of(2026, 9, 26, 10, 0);

  @Autowired private TestEntityManager entityManager;

  @Autowired private ProblemAttemptRepository problemAttemptRepository;

  private Problem problem(Level level, String targetText) {
    return entityManager.persist(new Problem(level, targetText));
  }

  /** 단계 학습 풀이 — 세션의 커리큘럼 단계가 붙는다 */
  private void freeAttempt(
      Problem problem, long sessionId, int itemNo, Long stageId, boolean correct) {
    entityManager.persist(
        ProblemAttempt.forFreeSession(
            USER_ID, problem.getId(), sessionId, itemNo, stageId, correct, ATTEMPTED_AT));
  }

  @Test
  void 문제의_단계로_묶어_푼_수와_맞힌_수를_센다() {
    Problem consonant = problem(Level.CONSONANT, "ㄱ");
    Problem word = problem(Level.WORD, "가방");
    freeAttempt(consonant, 1L, 1, 1L, true);
    freeAttempt(consonant, 1L, 2, 1L, false);
    freeAttempt(word, 1L, 3, 1L, true);

    List<LevelAttemptCount> counts = problemAttemptRepository.countByLevel(USER_ID);

    assertThat(counts)
        .extracting(
            LevelAttemptCount::getLevel,
            LevelAttemptCount::getSolvedCount,
            LevelAttemptCount::getCorrectCount)
        .containsExactlyInAnyOrder(
            tuple(Level.CONSONANT, 2L, 1L), tuple(Level.WORD, 1L, 1L));
  }

  @Test
  void 복습_세션_풀이도_센다() {
    // 복습 세션은 curriculum_stage_id 가 NULL 이다(ProblemService.createSession(userId, null)).
    // 단계로 묶는 countByStage 는 그 풀이를 통째로 버리지만, 문제 자신의 단계로 묶는 이 쿼리는
    // 제자리에 넣는다 — 이것이 "내 실력"을 이 축으로 바꾼 이유다
    Problem consonant = problem(Level.CONSONANT, "ㄱ");
    freeAttempt(consonant, 2L, 1, null, true);

    List<LevelAttemptCount> counts = problemAttemptRepository.countByLevel(USER_ID);

    assertThat(counts).hasSize(1);
    assertThat(counts.getFirst().getLevel()).isEqualTo(Level.CONSONANT);
    assertThat(counts.getFirst().getSolvedCount()).isEqualTo(1L);
  }

  @Test
  void 다른_사용자의_풀이는_세지_않는다() {
    Problem consonant = problem(Level.CONSONANT, "ㄱ");
    freeAttempt(consonant, 3L, 1, 1L, true);
    entityManager.persist(
        ProblemAttempt.forFreeSession(
            USER_ID + 1, consonant.getId(), 4L, 1, 1L, true, ATTEMPTED_AT));

    List<LevelAttemptCount> counts = problemAttemptRepository.countByLevel(USER_ID);

    assertThat(counts).hasSize(1);
    assertThat(counts.getFirst().getSolvedCount()).isEqualTo(1L);
  }

  @Test
  void 한_번도_맞히지_못한_단계도_0으로_센다() {
    // SUM(CASE ...) 이 NULL 을 돌려주면 화면의 정답률이 비어 버린다
    Problem sentence = problem(Level.SENTENCE, "나는 학교에 간다");
    freeAttempt(sentence, 5L, 1, 1L, false);

    List<LevelAttemptCount> counts = problemAttemptRepository.countByLevel(USER_ID);

    assertThat(counts.getFirst().getCorrectCount()).isZero();
  }

  @Test
  void 풀이가_없으면_빈_목록이다() {
    assertThat(problemAttemptRepository.countByLevel(USER_ID)).isEmpty();
  }
}
