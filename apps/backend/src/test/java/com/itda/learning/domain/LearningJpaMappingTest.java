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
  private static final String KEY = "0b3f8a1c-1111-4222-8333-444455556666";

  @Autowired private TestEntityManager entityManager;

  /** Attempt는 Cell 컬렉션을 소유하지 않으므로 attempt_cells는 attempt_id FK로 따라간다. */
  @Test
  void 풀이와_Cell은_attempt_id_FK로_연결해_저장한다() {
    List<CellSubmission> submissions =
        List.of(CellSubmission.of(0b000111, 0b000101), CellSubmission.of(0, 0));
    ProblemAttempt saved =
        entityManager.persistFlushFind(
            ProblemAttempt.forFreeSession(1L, 2L, 3L, 4, 5L, submissions, 1500, KEY, NOW));
    for (int index = 0; index < submissions.size(); index++) {
      entityManager.persist(new AttemptCell(saved, index + 1, submissions.get(index), NOW));
    }
    entityManager.flush();
    entityManager.clear();

    ProblemAttempt found = entityManager.find(ProblemAttempt.class, saved.getId());
    List<AttemptCell> cells =
        entityManager
            .getEntityManager()
            .createQuery(
                "select c from AttemptCell c where c.attempt.id = :id order by c.cellIndex asc",
                AttemptCell.class)
            .setParameter("id", saved.getId())
            .getResultList();

    assertThat(found.getAttemptMode()).isEqualTo(AttemptMode.FREE);
    assertThat(found.isCorrect()).isFalse();
    // 2 Cell = 점 위치 12개, 오류 점 1개 → (12-1)/12 = 0.9167
    assertThat(found.getScore()).isEqualByComparingTo(new BigDecimal("0.9167"));
    assertThat(found.getDurationMs()).isEqualTo(1500);
    assertThat(found.getIdempotencyKey()).isEqualTo(KEY);

    assertThat(cells).extracting(AttemptCell::getCellIndex).containsExactly(1, 2);
    assertThat(cells.get(0).getDiffMask().getValue()).isEqualTo(0b000010);
    assertThat(cells.get(0).getRespondedAt()).isEqualTo(NOW);
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
}
