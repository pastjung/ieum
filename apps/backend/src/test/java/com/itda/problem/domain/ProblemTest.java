package com.itda.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Problem은 현재 검증 없는 공개 생성자만 제공한다. 단계·목표 텍스트 검증은 도메인에 없으므로 여기서 다루지 않는다 — 호출하는 쪽의 책임이다. */
class ProblemTest {

  @Test
  void 생성자가_받은_값을_그대로_보관한다() {
    Problem problem = new Problem(Level.WORD, "가방");

    assertThat(problem.getLevel()).isEqualTo(Level.WORD);
    assertThat(problem.getTargetText()).isEqualTo("가방");
  }

  @Test
  void 등록_시각은_저장_전까지_비어_있다() {
    Problem problem = new Problem(Level.WORD, "가방");

    assertThat(problem.getCreatedAt()).isNull();
  }
}
