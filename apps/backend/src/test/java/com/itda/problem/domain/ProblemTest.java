package com.itda.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Problem은 현재 검증 없는 공개 생성자만 제공한다. 출처·유형·목표 텍스트 검증과 "source_type은 서버가 결정한다"(ERD 4.3절)는 규칙은
 * 도메인에 없으므로 여기서 다루지 않는다 — 호출하는 쪽의 책임이다.
 */
class ProblemTest {

  @Test
  void 생성자가_받은_값을_그대로_보관한다() {
    Problem problem =
        new Problem(7L, ProblemSourceType.INSTRUCTOR, ProblemType.WORD, "가방");

    assertThat(problem.getCreatorUserId()).isEqualTo(7L);
    assertThat(problem.getSourceType()).isEqualTo(ProblemSourceType.INSTRUCTOR);
    assertThat(problem.getProblemType()).isEqualTo(ProblemType.WORD);
    assertThat(problem.getTargetText()).isEqualTo("가방");
  }

  @Test
  void SYSTEM_문제는_작성자가_NULL이다() {
    Problem problem = new Problem(null, ProblemSourceType.SYSTEM, ProblemType.CONSONANT, "ㄱ");

    assertThat(problem.getSourceType()).isEqualTo(ProblemSourceType.SYSTEM);
    assertThat(problem.getCreatorUserId()).isNull();
  }

  @Test
  void 등록_시각은_저장_전까지_비어_있다() {
    Problem problem = new Problem(null, ProblemSourceType.SYSTEM, ProblemType.WORD, "가방");

    assertThat(problem.getCreatedAt()).isNull();
  }
}
