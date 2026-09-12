package com.itda.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

  /** U+1F600. code point 1개지만 UTF-16 code unit은 2개다. */
  private static final String SUPPLEMENTARY = new String(Character.toChars(0x1F600));

  private static String asciiOfLength(int length) {
    return "a".repeat(length);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 14})
  void 정규화_후_15_code_point_미만은_TOO_SHORT다(int length) {
    Optional<PasswordPolicyViolation> violation =
        PasswordPolicy.checkLength(PasswordPolicy.normalize(asciiOfLength(length)));

    assertThat(violation).contains(PasswordPolicyViolation.TOO_SHORT);
  }

  @ParameterizedTest
  @ValueSource(ints = {15, 16, 63, 64})
  void 정규화_후_15에서_64_code_point는_허용한다(int length) {
    Optional<PasswordPolicyViolation> violation =
        PasswordPolicy.checkLength(PasswordPolicy.normalize(asciiOfLength(length)));

    assertThat(violation).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {65, 100})
  void 정규화_후_64_code_point_초과는_TOO_LONG이다(int length) {
    Optional<PasswordPolicyViolation> violation =
        PasswordPolicy.checkLength(PasswordPolicy.normalize(asciiOfLength(length)));

    assertThat(violation).contains(PasswordPolicyViolation.TOO_LONG);
  }

  @Test
  void supplementary_character는_code_unit이_아니라_code_point로_센다() {
    String fifteenEmojis = SUPPLEMENTARY.repeat(15);

    assertThat(fifteenEmojis.length()).isEqualTo(30);
    assertThat(PasswordPolicy.checkLength(PasswordPolicy.normalize(fifteenEmojis))).isEmpty();
  }

  @Test
  void supplementary_character_14개는_code_unit이_28이어도_TOO_SHORT다() {
    String fourteenEmojis = SUPPLEMENTARY.repeat(14);

    assertThat(fourteenEmojis.length()).isEqualTo(28);
    assertThat(PasswordPolicy.checkLength(PasswordPolicy.normalize(fourteenEmojis)))
        .contains(PasswordPolicyViolation.TOO_SHORT);
  }

  @Test
  void supplementary_character_65개는_TOO_LONG이다() {
    assertThat(PasswordPolicy.checkLength(PasswordPolicy.normalize(SUPPLEMENTARY.repeat(65))))
        .contains(PasswordPolicyViolation.TOO_LONG);
  }

  @Test
  void 결합_문자는_NFC로_합쳐진_뒤의_길이로_판정한다() {
    // "e" + U+0301(결합 악센트) 15쌍. 정규화 전에는 30 code point, NFC 후에는 15 code point다.
    String decomposed = "é".repeat(15);
    String normalized = PasswordPolicy.normalize(decomposed);

    assertThat(decomposed.codePointCount(0, decomposed.length())).isEqualTo(30);
    assertThat(normalized.codePointCount(0, normalized.length())).isEqualTo(15);
    assertThat(PasswordPolicy.checkLength(normalized)).isEmpty();
  }

  @Test
  void 결합_문자_14쌍은_NFC_후_14_code_point이므로_TOO_SHORT다() {
    assertThat(PasswordPolicy.checkLength(PasswordPolicy.normalize("é".repeat(14))))
        .contains(PasswordPolicyViolation.TOO_SHORT);
  }

  @Test
  void 정규화는_앞뒤_공백을_보존한다() {
    String withSpaces = "  " + asciiOfLength(13) + "  ";

    String normalized = PasswordPolicy.normalize(withSpaces);

    assertThat(normalized).isEqualTo(withSpaces);
    assertThat(PasswordPolicy.checkLength(normalized)).isEmpty();
  }

  @Test
  void 공백만_있는_15자도_길이_정책을_통과한다() {
    assertThat(PasswordPolicy.checkLength(PasswordPolicy.normalize(" ".repeat(15)))).isEmpty();
  }

  @Test
  void 정규화는_대소문자를_바꾸지_않는다() {
    String mixedCase = "MixedCasePassPhrase";

    assertThat(PasswordPolicy.normalize(mixedCase)).isEqualTo(mixedCase);
  }

  @Test
  void 문자_종류_조합을_강제하지_않는다() {
    assertThat(PasswordPolicy.checkLength(PasswordPolicy.normalize("aaaaaaaaaaaaaaa"))).isEmpty();
    assertThat(PasswordPolicy.checkLength(PasswordPolicy.normalize("111111111111111"))).isEmpty();
    assertThat(PasswordPolicy.checkLength(PasswordPolicy.normalize("한글로만적은열다섯글자비밀번호"))).isEmpty();
  }

  @Test
  void 빈_문자열은_0_code_point이므로_TOO_SHORT다() {
    assertThat(PasswordPolicy.checkLength(PasswordPolicy.normalize("")))
        .contains(PasswordPolicyViolation.TOO_SHORT);
  }

  @Test
  void null_입력은_예외이며_예외_message에_입력값을_넣지_않는다() {
    assertThatThrownBy(() -> PasswordPolicy.normalize(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("비밀번호 값이 없습니다.");
  }
}
