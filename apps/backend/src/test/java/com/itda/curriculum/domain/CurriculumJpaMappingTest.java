package com.itda.curriculum.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.problem.domain.ProblemType;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
class CurriculumJpaMappingTest {

  @Autowired private TestEntityManager entityManager;

  @Test
  void curriculum_stages_행을_저장하고_다시_읽는다() {
    CurriculumStage saved =
        entityManager.persistFlushFind(stage("한글 자모", ProblemType.CONSONANT, 1, "자모 익히기"));
    entityManager.clear();

    CurriculumStage found = entityManager.find(CurriculumStage.class, saved.getId());

    assertThat(found.getName()).isEqualTo("한글 자모");
    assertThat(found.getLevel()).isEqualTo(ProblemType.CONSONANT);
    assertThat(found.getOrderNo()).isEqualTo(1);
    assertThat(found.getDescription()).isEqualTo("자모 익히기");
  }

  @Test
  void 단계의_level은_enum_이름_문자열로_저장한다() {
    CurriculumStage saved =
        entityManager.persistFlushFind(stage("단어", ProblemType.WORD, 2, null));
    entityManager.clear();

    Object stored =
        entityManager
            .getEntityManager()
            .createNativeQuery("select level from curriculum_stages where id = :id")
            .setParameter("id", saved.getId())
            .getSingleResult();

    assertThat(stored).isEqualTo("WORD");
  }

  @Test
  void 단계_순서는_전체에서_고유하다() {
    entityManager.persistAndFlush(stage("자음", ProblemType.CONSONANT, 9, null));

    assertThatThrownBy(
            () -> entityManager.persistAndFlush(stage("모음", ProblemType.VOWEL, 9, null)))
        .isInstanceOf(PersistenceException.class);
  }

  @Test
  void 설명은_비워둘_수_있다() {
    CurriculumStage saved = entityManager.persistFlushFind(stage("문장", ProblemType.SENTENCE, 3, null));
    entityManager.clear();

    assertThat(entityManager.find(CurriculumStage.class, saved.getId()).getDescription()).isNull();
  }

  @Test
  void curriculum_problems는_단계_문제_복합_PK로_저장한다() {
    entityManager.persistAndFlush(CurriculumProblem.linkAsDefault(5L, 101L, 3));
    entityManager.clear();

    CurriculumProblem found =
        entityManager.find(CurriculumProblem.class, CurriculumProblemId.of(5L, 101L));

    assertThat(found).isNotNull();
    assertThat(found.getDefaultOrderNo()).isEqualTo(3);
    assertThat(found.isDefaultProblem()).isTrue();
  }

  @Test
  void 기본_문제가_아닌_연결은_순번이_NULL이다() {
    entityManager.persistAndFlush(CurriculumProblem.link(5L, 102L));
    entityManager.clear();

    CurriculumProblem found =
        entityManager.find(CurriculumProblem.class, CurriculumProblemId.of(5L, 102L));

    assertThat(found.getDefaultOrderNo()).isNull();
    assertThat(found.isDefaultProblem()).isFalse();
  }

  /**
   * (curriculum_stage_id, default_order_no) 유니크는 NULL을 서로 다른 값으로 취급하는 표준 동작에 기대어, 기본 문제에만 적용되는 부분
   * 유니크로 동작한다. 한 단계가 출제 후보를 여러 개 갖는 것이 ERD 4.2절의 요구사항이므로 이 동작을 고정한다.
   */
  @Test
  void 같은_단계에_순번이_NULL인_출제_후보를_여러_개_연결할_수_있다() {
    entityManager.persist(CurriculumProblem.link(7L, 201L));
    entityManager.persist(CurriculumProblem.link(7L, 202L));
    entityManager.persist(CurriculumProblem.link(7L, 203L));

    entityManager.flush();
    entityManager.clear();

    assertThat(entityManager.find(CurriculumProblem.class, CurriculumProblemId.of(7L, 201L)))
        .isNotNull();
    assertThat(entityManager.find(CurriculumProblem.class, CurriculumProblemId.of(7L, 203L)))
        .isNotNull();
  }

  @Test
  void 같은_단계에_동일한_기본_문제_순번은_저장할_수_없다() {
    entityManager.persist(CurriculumProblem.linkAsDefault(8L, 301L, 1));
    entityManager.persist(CurriculumProblem.linkAsDefault(8L, 302L, 1));

    assertThatThrownBy(() -> entityManager.flush()).isInstanceOf(PersistenceException.class);
  }

  /**
   * CurriculumStage는 팩토리·공개 생성자가 없고 setter도 없다. 매핑만 검증하면 되므로 필드를 직접 채운다 — 도메인 규칙을 검증하는 것이
   * 아니라 컬럼·제약이 의도대로 걸렸는지만 확인한다.
   */
  private static CurriculumStage stage(
      String name, ProblemType level, int orderNo, String description) {
    CurriculumStage stage = new CurriculumStage();
    ReflectionTestUtils.setField(stage, "name", name);
    ReflectionTestUtils.setField(stage, "level", level);
    ReflectionTestUtils.setField(stage, "orderNo", orderNo);
    ReflectionTestUtils.setField(stage, "description", description);
    return stage;
  }
}
