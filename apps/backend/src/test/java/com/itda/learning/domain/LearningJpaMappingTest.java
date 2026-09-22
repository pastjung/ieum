package com.itda.learning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

@DataJpaTest
class LearningJpaMappingTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 12, 0);

  @Autowired private TestEntityManager entityManager;

  @Test
  void 풀이를_저장하면_Cell과_요소_평가가_cascade로_함께_저장된다() {
    ProblemAttempt attempt =
        ProblemAttempt.forFreeSession(
            1L,
            2L,
            3L,
            4,
            5L,
            List.of(CellSubmission.of(0b000111, 0b000101), CellSubmission.of(0, 0)),
            NOW);
    attempt.addSkillResult(10L, false, 1);
    attempt.addSkillResult(11L, true, 0);

    ProblemAttempt saved = entityManager.persistFlushFind(attempt);
    entityManager.clear();

    ProblemAttempt found = entityManager.find(ProblemAttempt.class, saved.getId());

    assertThat(found.getAttemptMode()).isEqualTo(AttemptMode.FREE);
    assertThat(found.isCorrect()).isFalse();

    assertThat(found.getCells()).hasSize(2);
    assertThat(found.getCells()).extracting(AttemptCell::getCellIndex).containsExactly(1, 2);
    assertThat(found.getCells().get(0).getDiffMask().getValue()).isEqualTo(0b000010);
    assertThat(found.getCells().get(0).getRespondedAt()).isEqualTo(NOW);

    assertThat(found.getSkillResults())
        .extracting(AttemptSkillResult::getSkillId)
        .containsExactlyInAnyOrder(10L, 11L);
    assertThat(found.getSkillResults())
        .allSatisfy(result -> assertThat(result.getEvaluatedAt()).isEqualTo(NOW));
  }

  @Test
  void LIVE_풀이는_수업만_채우고_세션_연결을_비운다() {
    ProblemAttempt saved =
        entityManager.persistFlushFind(
            ProblemAttempt.forLiveLecture(
                1L, 2L, 9L, List.of(CellSubmission.of(63, 63)), NOW));
    entityManager.clear();

    ProblemAttempt found = entityManager.find(ProblemAttempt.class, saved.getId());

    assertThat(found.getAttemptMode()).isEqualTo(AttemptMode.LIVE);
    assertThat(found.getLectureId()).isEqualTo(9L);
    assertThat(found.getLearningSessionId()).isNull();
    assertThat(found.getSessionItemNo()).isNull();
    assertThat(found.getCurriculumStageId()).isNull();
    assertThat(found.isCorrect()).isTrue();
  }

  @Test
  void user_skill_memory는_사용자_요소_복합_PK로_저장하고_확장_컬럼은_NULL이다() {
    entityManager.persistAndFlush(
        UserSkillMemory.firstObservation(
            1L, 10L, true, new BigDecimal("0.640000"), "BKT_V1", NOW, NOW));
    entityManager.clear();

    UserSkillMemory found =
        entityManager.find(UserSkillMemory.class, UserSkillMemoryId.of(1L, 10L));

    assertThat(found).isNotNull();
    assertThat(found.getTotalSeen()).isEqualTo(1);
    assertThat(found.getTotalCorrect()).isEqualTo(1);
    assertThat(found.getTotalIncorrect()).isZero();
    assertThat(found.getMasteryProbability()).isEqualByComparingTo(new BigDecimal("0.640000"));
    assertThat(found.getAlgorithmVersion()).isEqualTo("BKT_V1");
    assertThat(found.masteryBand()).isEqualTo(MasteryBand.WEAK);
    assertThat(found.getEstimatedHalfLife()).isNull();
    assertThat(found.getNextReviewAt()).isNull();
  }
}
