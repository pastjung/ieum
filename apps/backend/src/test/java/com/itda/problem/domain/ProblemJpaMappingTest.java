package com.itda.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.problem.repository.ProblemCellRepository;
import jakarta.persistence.PersistenceException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

@DataJpaTest
class ProblemJpaMappingTest {

  @Autowired private TestEntityManager entityManager;

  @Autowired private ProblemCellRepository problemCellRepository;

  @Test
  void 정답_Cell은_problem_id로_문제를_참조해_저장된다() {
    LocalDateTime before = LocalDateTime.now();
    Problem saved = persistWithCells(ProblemType.WORD, "가방", List.of(0b000001, 0b100000));
    entityManager.clear();

    Problem found = entityManager.find(Problem.class, saved.getId());
    List<ProblemCell> cells =
        problemCellRepository.findByProblemIdOrderByCellIndexAsc(saved.getId());

    assertThat(found.getSourceType()).isEqualTo(ProblemSourceType.SYSTEM);
    assertThat(found.getProblemType()).isEqualTo(ProblemType.WORD);
    assertThat(found.getTargetText()).isEqualTo("가방");
    assertThat(found.getCreatedAt()).isNotNull().isAfterOrEqualTo(before);
    assertThat(cells).hasSize(2);
    assertThat(cells).extracting(ProblemCell::getCellIndex).containsExactly(1, 2);
    assertThat(cells)
        .extracting(ProblemCell::getExpectedPattern)
        .containsExactly(0b000001, 0b100000);
    assertThat(cells)
        .allSatisfy(cell -> assertThat(cell.getProblem().getId()).isEqualTo(saved.getId()));
  }

  @Test
  void 정답_패턴은_TINYINT_컬럼_하나로_매핑된다() {
    Problem saved = persistWithCells(ProblemType.VOWEL, "ㅏ", List.of(0b000111));
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
    Problem saved = persistWithCells(ProblemType.VOWEL, "ㅏ", List.of(0));
    entityManager.clear();

    List<ProblemCell> cells =
        problemCellRepository.findByProblemIdOrderByCellIndexAsc(saved.getId());

    assertThat(cells.get(0).getExpectedPattern()).isZero();
  }

  @Test
  void 강사_등록_문제는_작성자와_함께_저장된다() {
    Problem saved =
        entityManager.persistFlushFind(
            Problem.registerByInstructor(9L, ProblemType.CONSONANT, "ㄱ"));
    entityManager.clear();

    Problem found = entityManager.find(Problem.class, saved.getId());

    assertThat(found.getSourceType()).isEqualTo(ProblemSourceType.INSTRUCTOR);
    assertThat(found.getCreatorUserId()).isEqualTo(9L);
  }

  @Test
  void learning_skills_행을_저장하고_다시_읽는다() {
    LearningSkill saved =
        entityManager.persistFlushFind(LearningSkill.of(SkillType.SKILL_01, "ㄱ", "초성 ㄱ", "초성 자리의 ㄱ"));
    entityManager.clear();

    LearningSkill found = entityManager.find(LearningSkill.class, saved.getId());

    assertThat(found.getSkillType()).isEqualTo(SkillType.SKILL_01);
    assertThat(found.getMetadata()).isEqualTo("ㄱ");
    assertThat(found.getName()).isEqualTo("초성 ㄱ");
  }

  @Test
  void 같은_유형_같은_metadata는_한_행만_존재한다() {
    entityManager.persistAndFlush(LearningSkill.of(SkillType.SKILL_01, "ㄴ", "초성 ㄴ", null));

    assertThatThrownBy(
            () -> entityManager.persistAndFlush(LearningSkill.of(SkillType.SKILL_01, "ㄴ", "종성 ㄴ", null)))
        .isInstanceOf(PersistenceException.class);
  }

  @Test
  void 유형이_다르면_같은_metadata를_쓸_수_있다() {
    entityManager.persistAndFlush(LearningSkill.of(SkillType.SKILL_01, "ㄷ", "초성 ㄷ", null));

    LearningSkill other =
        entityManager.persistFlushFind(LearningSkill.of(SkillType.SKILL_02, "ㄷ", "ㄷ 규칙", null));

    assertThat(other.getId()).isNotNull();
  }

  @Test
  void problem_skills는_문제와_요소를_FK로_연결해_저장한다() {
    Problem problem =
        entityManager.persistFlushFind(Problem.registerAsSystem(ProblemType.CONSONANT, "ㄱ"));
    LearningSkill skill =
        entityManager.persistFlushFind(LearningSkill.of(SkillType.SKILL_03, "ㄱ", "초성 ㄱ", null));

    ProblemSkill saved = entityManager.persistFlushFind(new ProblemSkill(problem, skill));
    entityManager.clear();

    ProblemSkill found = entityManager.find(ProblemSkill.class, saved.getId());

    assertThat(found.getId()).isNotNull();
    assertThat(found.getProblem().getId()).isEqualTo(problem.getId());
    assertThat(found.getSkill().getId()).isEqualTo(skill.getId());
  }

  @Test
  void 같은_문제와_같은_요소는_한_번만_연결된다() {
    Problem problem =
        entityManager.persistFlushFind(Problem.registerAsSystem(ProblemType.CONSONANT, "ㄹ"));
    LearningSkill skill =
        entityManager.persistFlushFind(LearningSkill.of(SkillType.SKILL_04, "ㄹ", "초성 ㄹ", null));
    entityManager.persistAndFlush(new ProblemSkill(problem, skill));

    assertThatThrownBy(() -> entityManager.persistAndFlush(new ProblemSkill(problem, skill)))
        .isInstanceOf(PersistenceException.class);
  }

  @Test
  void 등록_시각은_저장_시점에_Hibernate가_채운다() {
    Problem problem = Problem.registerAsSystem(ProblemType.WORD, "자동");
    assertThat(problem.getCreatedAt()).isNull();

    Problem saved = entityManager.persistFlushFind(problem);

    assertThat(saved.getCreatedAt()).isNotNull();
  }

  @Test
  void 정답_패턴은_6점을_모두_채운_63까지_저장한다() {
    Problem saved = persistWithCells(ProblemType.VOWEL, "ㅏ", List.of(0b111111));
    entityManager.clear();

    List<ProblemCell> cells =
        problemCellRepository.findByProblemIdOrderByCellIndexAsc(saved.getId());

    assertThat(cells.get(0).getExpectedPattern()).isEqualTo(63);
  }

  @Test
  void 한_문제에_여러_요소를_연결한다() {
    Problem problem =
        entityManager.persistFlushFind(Problem.registerAsSystem(ProblemType.WORD, "가방"));
    LearningSkill first =
        entityManager.persistFlushFind(LearningSkill.of(SkillType.SKILL_05, "ㄱ", "초성 ㄱ", null));
    LearningSkill second =
        entityManager.persistFlushFind(LearningSkill.of(SkillType.SKILL_05, "ㅏ", "중성 ㅏ", null));

    entityManager.persistAndFlush(new ProblemSkill(problem, first));
    entityManager.persistAndFlush(new ProblemSkill(problem, second));
    entityManager.clear();

    List<ProblemSkill> links =
        entityManager
            .getEntityManager()
            .createQuery("select ps from ProblemSkill ps where ps.problem.id = :id", ProblemSkill.class)
            .setParameter("id", problem.getId())
            .getResultList();

    assertThat(links).hasSize(2);
    assertThat(links)
        .extracting(link -> link.getSkill().getId())
        .containsExactlyInAnyOrder(first.getId(), second.getId());
  }

  @Test
  void 같은_요소를_여러_문제가_참조한다() {
    LearningSkill skill =
        entityManager.persistFlushFind(LearningSkill.of(SkillType.SKILL_06, "ㅁ", "초성 ㅁ", null));
    Problem first =
        entityManager.persistFlushFind(Problem.registerAsSystem(ProblemType.WORD, "마을"));
    Problem second =
        entityManager.persistFlushFind(Problem.registerAsSystem(ProblemType.WORD, "머리"));

    entityManager.persistAndFlush(new ProblemSkill(first, skill));
    entityManager.persistAndFlush(new ProblemSkill(second, skill));
    entityManager.clear();

    Long count =
        entityManager
            .getEntityManager()
            .createQuery("select count(ps) from ProblemSkill ps where ps.skill.id = :id", Long.class)
            .setParameter("id", skill.getId())
            .getSingleResult();

    assertThat(count).isEqualTo(2);
  }

  private Problem persistWithCells(
      ProblemType problemType, String targetText, List<Integer> expectedPatterns) {
    Problem saved =
        entityManager.persistFlushFind(Problem.registerAsSystem(problemType, targetText));
    for (int index = 0; index < expectedPatterns.size(); index++) {
      entityManager.persist(new ProblemCell(saved, index + 1, expectedPatterns.get(index)));
    }
    entityManager.flush();
    return saved;
  }
}
