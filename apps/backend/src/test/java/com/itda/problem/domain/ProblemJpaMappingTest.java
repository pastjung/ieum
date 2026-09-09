package com.itda.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

@DataJpaTest
class ProblemJpaMappingTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 12, 0);

  @Autowired private TestEntityManager entityManager;

  @Test
  void 문제를_저장하면_정답_Cell이_cascade로_함께_저장된다() {
    Problem saved =
        entityManager.persistFlushFind(
            Problem.registerAsSystem(ProblemType.WORD, "가방", List.of(0b000001, 0b100000), NOW));
    entityManager.clear();

    Problem found = entityManager.find(Problem.class, saved.getId());

    assertThat(found.getSourceType()).isEqualTo(ProblemSourceType.SYSTEM);
    assertThat(found.getProblemType()).isEqualTo(ProblemType.WORD);
    assertThat(found.getTargetText()).isEqualTo("가방");
    assertThat(found.getCreatedAt()).isEqualTo(NOW);
    assertThat(found.getCells()).hasSize(2);
    assertThat(found.getCells()).extracting(ProblemCell::getCellIndex).containsExactly(1, 2);
    assertThat(found.getCells())
        .extracting(cell -> cell.getExpectedPattern().getValue())
        .containsExactly(0b000001, 0b100000);
  }

  @Test
  void 정답_패턴은_TINYINT_컬럼_하나로_매핑된다() {
    Problem saved =
        entityManager.persistFlushFind(
            Problem.registerAsSystem(ProblemType.DOT_PATTERN, "점 1-2-3", List.of(0b000111), NOW));
    entityManager.clear();

    Object stored =
        entityManager
            .getEntityManager()
            .createNativeQuery("select expected_pattern from problem_cells where problem_id = :id")
            .setParameter("id", saved.getId())
            .getSingleResult();

    assertThat(((Number) stored).intValue()).isEqualTo(0b000111);
  }

  @Test
  void 빈_점형_정답도_0으로_저장된다() {
    Problem saved =
        entityManager.persistFlushFind(
            Problem.registerAsSystem(ProblemType.DOT_PATTERN, "빈 칸", List.of(0), NOW));
    entityManager.clear();

    Problem found = entityManager.find(Problem.class, saved.getId());

    assertThat(found.getCells().get(0).getExpectedPattern().isEmpty()).isTrue();
  }

  @Test
  void 강사_등록_문제는_작성자와_멱등_키가_함께_저장된다() {
    String key = "0b3f8a1c-1111-4222-8333-444455556666";

    Problem saved =
        entityManager.persistFlushFind(
            Problem.registerByInstructor(9L, key, ProblemType.JAMO, "ㄱ", List.of(1), NOW));
    entityManager.clear();

    Problem found = entityManager.find(Problem.class, saved.getId());

    assertThat(found.getSourceType()).isEqualTo(ProblemSourceType.INSTRUCTOR);
    assertThat(found.getCreatorUserId()).isEqualTo(9L);
    assertThat(found.getCreationIdempotencyKey()).isEqualTo(key);
  }

  @Test
  void learning_skills_행을_저장하고_다시_읽는다() {
    LearningSkill saved =
        entityManager.persistFlushFind(
            LearningSkill.of(SkillType.JAMO, "JAMO_CHOSEONG_G", "초성 ㄱ", "초성 자리의 ㄱ"));
    entityManager.clear();

    LearningSkill found = entityManager.find(LearningSkill.class, saved.getId());

    assertThat(found.getSkillType()).isEqualTo(SkillType.JAMO);
    assertThat(found.getSkillCode()).isEqualTo("JAMO_CHOSEONG_G");
    assertThat(found.getName()).isEqualTo("초성 ㄱ");
  }

  @Test
  void problem_skills는_문제_요소_복합_PK로_저장한다() {
    entityManager.persistAndFlush(ProblemSkill.link(101L, 10L));
    entityManager.clear();

    ProblemSkill found = entityManager.find(ProblemSkill.class, ProblemSkillId.of(101L, 10L));

    assertThat(found).isNotNull();
    assertThat(found.getId().getProblemId()).isEqualTo(101L);
    assertThat(found.getId().getSkillId()).isEqualTo(10L);
  }
}
