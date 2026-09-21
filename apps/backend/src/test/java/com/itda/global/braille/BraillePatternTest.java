package com.itda.global.braille;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 6점 셀 비교는 채점의 기준이므로 경계값을 우선 검증한다 (docs/conventions/quality/testing.md). */
class BraillePatternTest {

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 32, 62, 63})
  void 허용_범위_0부터_63까지는_생성된다(int value) {
    assertThat(BraillePattern.of(value).getValue()).isEqualTo(value);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 64, 255})
  void 허용_범위를_벗어난_값은_거부한다(int value) {
    assertThatThrownBy(() -> BraillePattern.of(value)).isInstanceOf(BusinessException.class);
  }

  @Test
  void 값_0은_아무_점도_선택하지_않은_빈_점형이다() {
    assertThat(BraillePattern.of(0).isEmpty()).isTrue();
    assertThat(BraillePattern.empty().getValue()).isZero();
  }

  @Test
  void 차이_마스크는_두_패턴의_XOR이다() {
    BraillePattern expected = BraillePattern.of(0b000111);
    BraillePattern input = BraillePattern.of(0b000101);

    assertThat(expected.diff(input).getValue()).isEqualTo(0b000010);
  }

  @Test
  void 같은_패턴의_차이_마스크는_비어_있다() {
    BraillePattern pattern = BraillePattern.of(63);

    assertThat(pattern.diff(BraillePattern.of(63)).isEmpty()).isTrue();
  }

  @Test
  void 모두_다른_패턴의_차이는_여섯_점_전부다() {
    BraillePattern allDots = BraillePattern.of(63);

    assertThat(allDots.diff(BraillePattern.empty()).dotCount()).isEqualTo(6);
  }

  @Test
  void 점_개수는_켜진_비트_수다() {
    assertThat(BraillePattern.empty().dotCount()).isZero();
    assertThat(BraillePattern.of(0b010101).dotCount()).isEqualTo(3);
    assertThat(BraillePattern.of(63).dotCount()).isEqualTo(6);
  }

  @Test
  void bit_0부터_5가_점_1부터_6에_대응한다() {
    // 점 1과 점 4만 켠 패턴 = 1 + 8
    BraillePattern pattern = BraillePattern.of(0b001001);

    assertThat(pattern.hasDot(1)).isTrue();
    assertThat(pattern.hasDot(2)).isFalse();
    assertThat(pattern.hasDot(3)).isFalse();
    assertThat(pattern.hasDot(4)).isTrue();
    assertThat(pattern.hasDot(5)).isFalse();
    assertThat(pattern.hasDot(6)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 7})
  void 점_번호가_1부터_6을_벗어나면_거부한다(int dotNumber) {
    assertThatThrownBy(() -> BraillePattern.of(63).hasDot(dotNumber))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 같은_값의_패턴은_동등하다() {
    assertThat(BraillePattern.of(42)).isEqualTo(BraillePattern.of(42));
    assertThat(BraillePattern.of(42)).isNotEqualTo(BraillePattern.of(43));
  }
}
