package com.itda.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 계정 생성 입력의 아이디 규칙을 고정한다 (API 명세 3.2.1절). */
class UsernameTest {

  // ---------- 정규화 ----------

  @Test
  void 앞뒤_공백을_제거한다() {
    assertThat(Username.of("  learner01\t\n").value()).isEqualTo("learner01");
  }

  @Test
  void NFC로_정규화한다() {
    // 조합형(NFD)으로 들어온 라틴 확장 문자는 NFC에서 한 글자로 합쳐진다.
    String decomposed = Normalizer.normalize("café-01", Normalizer.Form.NFD);
    assertThat(decomposed).isNotEqualTo(Normalizer.normalize(decomposed, Normalizer.Form.NFC));

    // 합쳐진 é는 ASCII가 아니므로 거부된다. 여기서 확인하는 것은 "정규화를 먼저 한다"는 순서다.
    assertThatThrownBy(() -> Username.of(decomposed)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 정규화_후_길이로_판정한다() {
    // 공백을 포함하면 33자지만 trim 후 30자라 통과해야 한다.
    String padded = "  " + "a".repeat(30) + " ";

    assertThat(Username.of(padded).value()).hasSize(30);
  }

  // ---------- 길이 경계 ----------

  @ParameterizedTest
  @ValueSource(strings = {"abc", "a1b", "a.c", "a_c", "a-c"})
  void 최소_길이_3자를_허용한다(String value) {
    assertThat(Username.of(value).value()).isEqualTo(value);
  }

  @Test
  void 최대_길이_30자를_허용한다() {
    String value = "a".repeat(30);

    assertThat(Username.of(value).value()).isEqualTo(value);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ab", "a", "  a  "})
  void 두_자_이하를_거부한다(String value) {
    assertThatThrownBy(() -> Username.of(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 서른한_자_이상을_거부한다() {
    assertThatThrownBy(() -> Username.of("a".repeat(31)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- 허용 문자 ----------

  @ParameterizedTest
  @ValueSource(strings = {"learner01", "Learner.01", "a_b-c.d", "ABCdef123", "aaa"})
  void 허용_문자로만_이루어지면_통과한다(String value) {
    assertThatCode(() -> Username.of(value)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "learner 01", // 공백
        "learner@01", // @
        "learner+01", // +
        "learner#01",
        "learner/01",
        "learner\\01",
        "러너학습자", // 비ASCII
        "learnerＡ01" // 전각
      })
  void 금지_문자가_있으면_거부한다(String value) {
    assertThatThrownBy(() -> Username.of(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 제어_문자를_거부한다() {
    // 소스 리터럴로 두면 도구마다 다르게 다뤄지므로 실행 시점에 만든다.
    String withNul = "learner" + (char) 0 + "01";

    assertThatThrownBy(() -> Username.of(withNul)).isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- 처음·끝 문자 ----------

  @ParameterizedTest
  @ValueSource(strings = {".abc", "_abc", "-abc"})
  void 첫_문자가_영문_숫자가_아니면_거부한다(String value) {
    assertThatThrownBy(() -> Username.of(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc.", "abc_", "abc-"})
  void 마지막_문자가_영문_숫자가_아니면_거부한다(String value) {
    assertThatThrownBy(() -> Username.of(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"a.b", "1-9", "z_z", "a..b", "a--b"})
  void 가운데의_구분자는_허용한다(String value) {
    assertThat(Username.of(value).value()).isEqualTo(value);
  }

  // ---------- 누락 ----------

  @Test
  void null과_공백만_있는_값을_거부한다() {
    assertThatThrownBy(() -> Username.of(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Username.of("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Username.of("    ")).isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- 노출 ----------

  @Test
  void 거부_예외와_toString에_입력값이_없다() {
    String raw = "unacceptable user name";

    assertThatThrownBy(() -> Username.of(raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(raw)
        .hasMessageNotContaining("unacceptable");
    assertThat(Username.of("learner01")).hasToString("Username[REDACTED]");
  }

  @Test
  void 표기는_그대로_저장한다() {
    // 대소문자 비구분 UNIQUE는 DB collation이 맡는다. 값을 소문자로 바꾸면 사용자 표기를 잃는다.
    assertThat(Username.of("Learner01").value()).isEqualTo("Learner01");
  }
}
