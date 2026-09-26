package com.itda.problem.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.problem.domain.Level;
import com.itda.problem.domain.SkillType;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** displayText 규칙 (API 명세 8.2.1절). 입력 문자열이 아니라 level·학습 요소로 정한다. */
class ProblemDisplayTest {

  @Test
  void 첫소리_요소에_연결된_자음은_초성을_붙인다() {
    assertThat(ProblemDisplay.displayText(Level.CONSONANT, "ㄱ", Set.of(SkillType.SKILL_01)))
        .isEqualTo("초성 ㄱ");
  }

  @Test
  void 받침_요소에_연결된_자음은_받침을_붙인다() {
    assertThat(ProblemDisplay.displayText(Level.CONSONANT, "ㄱ", Set.of(SkillType.SKILL_03)))
        .isEqualTo("받침 ㄱ");
  }

  @Test
  void 첫소리와_받침_요소가_모두_있으면_초성을_붙인다() {
    assertThat(
            ProblemDisplay.displayText(
                Level.CONSONANT, "ㄱ", Set.of(SkillType.SKILL_01, SkillType.SKILL_03)))
        .isEqualTo("초성 ㄱ");
  }

  @Test
  void 둘_다_아닌_자음은_글자_그대로다() {
    assertThat(ProblemDisplay.displayText(Level.CONSONANT, "ㅆ", Set.of(SkillType.SKILL_04)))
        .isEqualTo("ㅆ");
  }

  @Test
  void 모음은_모음을_약자는_음절을_붙인다() {
    assertThat(ProblemDisplay.displayText(Level.VOWEL, "ㅏ", Set.of())).isEqualTo("모음 ㅏ");
    assertThat(ProblemDisplay.displayText(Level.CONTRACTION, "가", Set.of())).isEqualTo("음절 가");
  }

  @Test
  void 낱말_약어_문장은_글자_그대로다() {
    assertThat(ProblemDisplay.displayText(Level.WORD, "가", Set.of(SkillType.SKILL_01)))
        .isEqualTo("가");
    assertThat(ProblemDisplay.displayText(Level.CONTRACTION_WORD, "그리고", Set.of()))
        .isEqualTo("그리고");
    assertThat(ProblemDisplay.displayText(Level.SENTENCE, "나는 간다", Set.of())).isEqualTo("나는 간다");
  }
}
