package com.itda.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProblemTest {

  @Test
  void 목표_텍스트가_비어_있으면_거부한다() {
    assertThatThrownBy(() -> Problem.registerAsSystem(ProblemType.WORD, "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void SYSTEM_문제는_작성자가_NULL이다() {
    Problem problem = Problem.registerAsSystem(ProblemType.CONSONANT, "ㄱ");

    assertThat(problem.getSourceType()).isEqualTo(ProblemSourceType.SYSTEM);
    assertThat(problem.getCreatorUserId()).isNull();
  }

  @Test
  void 강사_등록_문제는_작성자가_필요하다() {
    Problem problem = Problem.registerByInstructor(7L, ProblemType.WORD, "가");

    assertThat(problem.getSourceType()).isEqualTo(ProblemSourceType.INSTRUCTOR);
    assertThat(problem.getCreatorUserId()).isEqualTo(7L);
  }

  @Test
  void 강사_등록에_작성자가_없으면_거부한다() {
    assertThatThrownBy(() -> Problem.registerByInstructor(null, ProblemType.WORD, "가"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
