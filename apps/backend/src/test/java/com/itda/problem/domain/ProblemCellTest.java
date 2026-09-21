package com.itda.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.global.braille.BraillePattern;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ProblemCell은 현재 검증 없는 공개 생성자만 제공한다. Cell 개수·순번 연속성·0~63 범위 검증이 도메인에 없으므로 여기서 다루지 않는다 — 호출하는
 * 쪽(ProblemService)의 책임이다.
 */
class ProblemCellTest {

  @Test
  void 문제_순번_정답_패턴을_그대로_보관한다() {
    Problem problem = new Problem(Level.WORD, "가방");

    ProblemCell cell = new ProblemCell(problem, 2, 0b100000);

    assertThat(cell.getProblem()).isSameAs(problem);
    assertThat(cell.getCellIndex()).isEqualTo(2);
    assertThat(cell.getExpectedPattern()).isEqualTo(0b100000);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 62, BraillePattern.MAX_VALUE})
  void 정답_패턴은_6비트_정수를_그대로_담는다(int pattern) {
    Problem problem = new Problem(Level.VOWEL, "ㅏ");

    assertThat(new ProblemCell(problem, 1, pattern).getExpectedPattern()).isEqualTo(pattern);
  }

  @Test
  void 빈_점형_0은_미입력이_아니라_명시적인_값이다() {
    Problem problem = new Problem(Level.VOWEL, "ㅏ");

    assertThat(new ProblemCell(problem, 1, 0).getExpectedPattern()).isZero();
  }

  @Test
  void 목록_순서대로_만들면_1부터의_Cell_순번이_된다() {
    Problem problem = new Problem(Level.WORD, "가방");
    List<Integer> patterns = List.of(0b000001, 0b100000, 0);

    List<ProblemCell> cells =
        IntStream.range(0, patterns.size())
            .mapToObj(index -> new ProblemCell(problem, index + 1, patterns.get(index)))
            .toList();

    assertThat(cells).extracting(ProblemCell::getCellIndex).containsExactly(1, 2, 3);
    assertThat(cells)
        .extracting(ProblemCell::getExpectedPattern)
        .containsExactly(0b000001, 0b100000, 0);
    assertThat(cells).allSatisfy(cell -> assertThat(cell.getProblem()).isSameAs(problem));
  }
}
