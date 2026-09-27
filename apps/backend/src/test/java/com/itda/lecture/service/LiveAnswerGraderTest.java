package com.itda.lecture.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.global.error.ErrorCode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LIVE 채점 규칙 (API 명세 8.2.2절). 경계값을 우선한다 (testing.md). */
class LiveAnswerGraderTest {

  @Test
  void 모든_칸이_같으면_정답이고_100점이다() {
    List<AnswerCell> cells = LiveAnswerGrader.grade(List.of(8, 3), List.of(8, 3));

    assertThat(LiveAnswerGrader.isCorrect(List.of(8, 3), List.of(8, 3))).isTrue();
    assertThat(LiveAnswerGrader.score(true, cells)).isEqualTo(100);
    assertThat(cells).extracting(AnswerCell::cellIndex).containsExactly(1, 2);
    assertThat(cells).extracting(AnswerCell::diffMask).containsExactly(0, 0);
  }

  @Test
  void 틀린_칸의_diffMask는_입력과_정답의_XOR이다() {
    AnswerCell cell = LiveAnswerGrader.grade(List.of(9), List.of(8)).getFirst();

    assertThat(cell.inputPattern()).isEqualTo(9);
    assertThat(cell.expectedPattern()).isEqualTo(8);
    assertThat(cell.diffMask()).isEqualTo(1);
    assertThat(cell.correct()).isFalse();
  }

  @Test
  void 입력이_짧으면_긴_쪽만큼_칸을_만들고_입력을_0으로_채운다() {
    List<AnswerCell> cells = LiveAnswerGrader.grade(List.of(8), List.of(8, 3));

    assertThat(cells).hasSize(2);
    assertThat(cells.get(1).inputPattern()).isZero();
    assertThat(cells.get(1).expectedPattern()).isEqualTo(3);
    assertThat(LiveAnswerGrader.isCorrect(List.of(8), List.of(8, 3))).isFalse();
    assertThat(LiveAnswerGrader.score(false, cells)).isEqualTo(50);
  }

  @Test
  void 입력이_길면_정답을_0으로_채우고_점수는_내림한다() {
    List<AnswerCell> cells = LiveAnswerGrader.grade(List.of(8, 3, 1), List.of(8, 3));

    assertThat(cells).hasSize(3);
    assertThat(cells.get(2).expectedPattern()).isZero();
    assertThat(LiveAnswerGrader.score(false, cells)).isEqualTo(66);
  }

  @Test
  void 맞은_칸이_없으면_0점이다() {
    assertThat(LiveAnswerGrader.score(false, LiveAnswerGrader.grade(List.of(1), List.of(2))))
        .isZero();
  }

  @Test
  void 한_칸부터_열두_칸까지_0에서_63은_유효하다() {
    assertThatCode(() -> LiveAnswerGrader.validate(List.of(0))).doesNotThrowAnyException();
    assertThatCode(() -> LiveAnswerGrader.validate(Collections.nCopies(12, 63)))
        .doesNotThrowAnyException();
  }

  @Test
  void 없거나_빈_cells는_422다() {
    assertInvalid(null);
    assertInvalid(List.of());
  }

  @Test
  void 열세_칸은_422다() {
    assertInvalid(Collections.nCopies(13, 1));
  }

  @Test
  void 범위_밖_값과_null_칸은_422다() {
    assertInvalid(List.of(64));
    assertInvalid(List.of(-1));
    assertInvalid(Arrays.asList(8, null));
  }

  @Test
  void 저장된_칸과_0을_채워_비교해_같은_답인지_본다() {
    List<AnswerCell> stored = LiveAnswerGrader.grade(List.of(8), List.of(8, 3));

    assertThat(LiveAnswerGrader.sameInput(List.of(8), stored)).isTrue();
    assertThat(LiveAnswerGrader.sameInput(List.of(8, 0), stored)).isTrue();
    assertThat(LiveAnswerGrader.sameInput(List.of(8, 0, 0), stored)).isFalse();
    assertThat(LiveAnswerGrader.sameInput(List.of(9), stored)).isFalse();
  }

  private static void assertInvalid(List<Integer> cells) {
    assertThatThrownBy(() -> LiveAnswerGrader.validate(cells))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_ANSWER_CELLS);
  }
}
