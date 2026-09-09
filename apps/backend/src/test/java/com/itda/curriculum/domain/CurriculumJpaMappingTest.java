package com.itda.curriculum.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
}
