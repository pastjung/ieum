package com.itda.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProblemTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 12, 0);
  private static final String KEY = "0b3f8a1c-1111-4222-8333-444455556666";

  @Test
  void 정답_패턴_목록의_순서가_1부터의_Cell_순번이_된다() {
    Problem problem =
        Problem.registerAsSystem(ProblemType.WORD, "가방", List.of(0b000001, 0b100000, 0), NOW);

    assertThat(problem.cellCount()).isEqualTo(3);
    assertThat(problem.getCells()).extracting(ProblemCell::getCellIndex).containsExactly(1, 2, 3);
    assertThat(problem.getCells())
        .extracting(cell -> cell.getExpectedPattern().getValue())
        .containsExactly(0b000001, 0b100000, 0);
  }

  @Test
  void Cell은_최대_6개다() {
    List<Integer> sevenCells = List.of(1, 2, 3, 4, 5, 6, 7);

    assertThatThrownBy(() -> Problem.registerAsSystem(ProblemType.SENTENCE, "문장", sevenCells, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void Cell이_없으면_거부한다() {
    assertThatThrownBy(() -> Problem.registerAsSystem(ProblemType.WORD, "가방", List.of(), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = ProblemType.class,
      names = {"DOT_POSITION", "DOT_PATTERN"})
  void 점_위치와_점_조합_문제는_정확히_1_Cell이어야_한다(ProblemType type) {
    assertThat(type.requiresSingleCell()).isTrue();
    assertThat(Problem.registerAsSystem(type, "점 1", List.of(1), NOW).cellCount()).isEqualTo(1);

    assertThatThrownBy(() -> Problem.registerAsSystem(type, "점 1", List.of(1, 2), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 범위를_벗어난_정답_패턴은_거부한다() {
    assertThatThrownBy(() -> Problem.registerAsSystem(ProblemType.WORD, "가방", List.of(64), NOW))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void 목표_텍스트가_비어_있으면_거부한다() {
    assertThatThrownBy(() -> Problem.registerAsSystem(ProblemType.WORD, "  ", List.of(1), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void SYSTEM_문제는_작성자와_멱등_키가_모두_NULL이다() {
    Problem problem = Problem.registerAsSystem(ProblemType.JAMO, "ㄱ", List.of(1), NOW);

    assertThat(problem.getSourceType()).isEqualTo(ProblemSourceType.SYSTEM);
    assertThat(problem.getCreatorUserId()).isNull();
    assertThat(problem.getCreationIdempotencyKey()).isNull();
  }

  @Test
  void AI_문제도_작성자와_멱등_키가_모두_NULL이다() {
    Problem problem = Problem.registerByAi(ProblemType.JAMO, "ㄴ", List.of(2), NOW);

    assertThat(problem.getSourceType()).isEqualTo(ProblemSourceType.AI);
    assertThat(problem.getCreatorUserId()).isNull();
    assertThat(problem.getCreationIdempotencyKey()).isNull();
  }

  @Test
  void 강사_등록_문제는_작성자와_멱등_키가_모두_필요하다() {
    Problem problem =
        Problem.registerByInstructor(7L, KEY, ProblemType.SYLLABLE, "가", List.of(1), NOW);

    assertThat(problem.getSourceType()).isEqualTo(ProblemSourceType.INSTRUCTOR);
    assertThat(problem.getCreatorUserId()).isEqualTo(7L);
    assertThat(problem.getCreationIdempotencyKey()).isEqualTo(KEY);
  }

  @Test
  void 강사_등록에_작성자가_없으면_거부한다() {
    assertThatThrownBy(
            () ->
                Problem.registerByInstructor(null, KEY, ProblemType.SYLLABLE, "가", List.of(1), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 강사_등록에_멱등_키가_없으면_거부한다() {
    assertThatThrownBy(
            () ->
                Problem.registerByInstructor(7L, null, ProblemType.SYLLABLE, "가", List.of(1), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void Cell_목록은_애그리거트_밖에서_바꿀_수_없다() {
    Problem problem = Problem.registerAsSystem(ProblemType.WORD, "가방", List.of(1, 2), NOW);

    assertThatThrownBy(() -> problem.getCells().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
