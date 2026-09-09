package com.itda.learning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

@DataJpaTest
class LearningJpaMappingTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 12, 0);
  private static final String KEY = "0b3f8a1c-1111-4222-8333-444455556666";

  @Autowired private TestEntityManager entityManager;

  private static List<AssignedProblem> validAssignment() {
    List<AssignedProblem> items = new ArrayList<>();
    long[] firstHalf = {101, 102, 103, 104, 105};
    long[] secondHalf = {103, 101, 105, 102, 104};
    for (int i = 0; i < firstHalf.length; i++) {
      items.add(AssignedProblem.of(i + 1, firstHalf[i]));
    }
    for (int i = 0; i < secondHalf.length; i++) {
      items.add(AssignedProblem.of(i + 6, secondHalf[i]));
    }
    return items;
  }

  @Test
  void 배정_JSON이_왕복해도_순서와_값이_유지된다() {
    LearningSession saved =
        entityManager.persistFlushFind(LearningSession.create(1L, 2L, validAssignment(), NOW));
    entityManager.clear();

    LearningSession found = entityManager.find(LearningSession.class, saved.getId());

    assertThat(found.getAssignedProblems()).hasSize(LearningSession.ITEM_COUNT);
    assertThat(found.getAssignedProblems())
        .extracting(AssignedProblem::itemNo)
        .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    // problem_id는 ERD 5.5절대로 10진수 문자열로 보존된다.
    assertThat(found.getAssignedProblems())
        .extracting(AssignedProblem::problemId)
        .containsExactly("101", "102", "103", "104", "105", "103", "101", "105", "102", "104");
  }

  @Test
  void 세션_상태는_시각_컬럼에서_파생되며_컬럼이_없다() {
    LearningSession session =
        entityManager.persistFlushFind(LearningSession.create(1L, 2L, validAssignment(), NOW));
    assertThat(session.status()).isEqualTo(SessionStatus.IN_PROGRESS);

    session.markCompleted(NOW.plusMinutes(30));
    session.markAnalysisApplied(NOW.plusMinutes(31));
    entityManager.flush();
    entityManager.clear();

    LearningSession found = entityManager.find(LearningSession.class, session.getId());

    assertThat(found.status()).isEqualTo(SessionStatus.ANALYZED);
    assertThat(found.getCompletedAt()).isEqualTo(NOW.plusMinutes(30));
    assertThat(found.getAnalysisAppliedAt()).isEqualTo(NOW.plusMinutes(31));
  }

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
            1500,
            KEY,
            NOW);
    attempt.addSkillResult(10L, false, 1);
    attempt.addSkillResult(11L, true, 0);

    ProblemAttempt saved = entityManager.persistFlushFind(attempt);
    entityManager.clear();

    ProblemAttempt found = entityManager.find(ProblemAttempt.class, saved.getId());

    assertThat(found.getAttemptMode()).isEqualTo(AttemptMode.FREE);
    assertThat(found.isCorrect()).isFalse();
    // 2 Cell = 점 위치 12개, 오류 점 1개 → (12-1)/12 = 0.9167
    assertThat(found.getScore()).isEqualByComparingTo(new BigDecimal("0.9167"));
    assertThat(found.getDurationMs()).isEqualTo(1500);
    assertThat(found.getIdempotencyKey()).isEqualTo(KEY);

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
                1L, 2L, 9L, List.of(CellSubmission.of(63, 63)), null, KEY, NOW));
    entityManager.clear();

    ProblemAttempt found = entityManager.find(ProblemAttempt.class, saved.getId());

    assertThat(found.getAttemptMode()).isEqualTo(AttemptMode.LIVE);
    assertThat(found.getLectureId()).isEqualTo(9L);
    assertThat(found.getLearningSessionId()).isNull();
    assertThat(found.getSessionItemNo()).isNull();
    assertThat(found.getCurriculumStageId()).isNull();
    assertThat(found.isCorrect()).isTrue();
    assertThat(found.getScore()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(found.getDurationMs()).isNull();
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
