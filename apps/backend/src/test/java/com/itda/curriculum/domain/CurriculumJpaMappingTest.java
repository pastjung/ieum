package com.itda.curriculum.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

@DataJpaTest
class CurriculumJpaMappingTest {

  @Autowired private TestEntityManager entityManager;

  @Test
  void curriculum_stages_행을_저장하고_다시_읽는다() {
    CurriculumStage saved =
        entityManager.persistFlushFind(CurriculumStage.of("한글 자모", "L1", 1, "자모 익히기"));
    entityManager.clear();

    CurriculumStage found = entityManager.find(CurriculumStage.class, saved.getId());

    assertThat(found.getName()).isEqualTo("한글 자모");
    assertThat(found.getLevel()).isEqualTo("L1");
    assertThat(found.getOrderNo()).isEqualTo(1);
    assertThat(found.getDescription()).isEqualTo("자모 익히기");
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
}
