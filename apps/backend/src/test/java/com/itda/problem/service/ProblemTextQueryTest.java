package com.itda.problem.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.problem.service.ProblemTextQuery.Prefix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 강사 입력 규칙: 구분어 + 공백 1개 이상 + 글자 (API 명세 8.2.1절). */
class ProblemTextQueryTest {

  @ParameterizedTest
  @CsvSource({"초성 ㄱ,ONSET,ㄱ", "받침 ㄱ,CODA,ㄱ", "모음 ㅏ,VOWEL,ㅏ", "음절 가,SYLLABLE,가"})
  void 구분어와_글자를_나눈다(String raw, Prefix prefix, String text) {
    ProblemTextQuery query = ProblemTextQuery.parse(raw);

    assertThat(query.prefix()).isEqualTo(prefix);
    assertThat(query.text()).isEqualTo(text);
  }

  @Test
  void 앞뒤_공백을_지우고_여러_칸_공백도_구분_공백으로_본다() {
    ProblemTextQuery query = ProblemTextQuery.parse("  초성   ㄱ  ");

    assertThat(query.prefix()).isEqualTo(Prefix.ONSET);
    assertThat(query.text()).isEqualTo("ㄱ");
  }

  @Test
  void 전각_공백도_구분_공백으로_본다() {
    ProblemTextQuery query = ProblemTextQuery.parse("받침　ㄱ");

    assertThat(query.prefix()).isEqualTo(Prefix.CODA);
    assertThat(query.text()).isEqualTo("ㄱ");
  }

  @Test
  void 공백_없이_붙으면_구분어로_보지_않는다() {
    ProblemTextQuery query = ProblemTextQuery.parse("초성ㄱ");

    assertThat(query.prefix()).isNull();
    assertThat(query.text()).isEqualTo("초성ㄱ");
  }

  @Test
  void 구분어만_있으면_낱말로_본다() {
    ProblemTextQuery query = ProblemTextQuery.parse("초성");

    assertThat(query.prefix()).isNull();
    assertThat(query.text()).isEqualTo("초성");
  }

  @ParameterizedTest
  @ValueSource(strings = {"ㄱ", "ㅎ", "ㅏ", "ㅣ", "ㅆ", " ㄱ "})
  void 구분어_없는_호환_자모_한_글자는_낱자다(String raw) {
    assertThat(ProblemTextQuery.parse(raw).isSingleJamo()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"가", "ㄱㄴ", "초성 ㄱ", "그리고", "a"})
  void 음절_여러_글자_구분어_입력은_낱자가_아니다(String raw) {
    assertThat(ProblemTextQuery.parse(raw).isSingleJamo()).isFalse();
  }

  @Test
  void 구분어_문구는_표시_문구와_같다() {
    assertThat(Prefix.ONSET.label()).isEqualTo("초성");
    assertThat(Prefix.CODA.label()).isEqualTo("받침");
    assertThat(Prefix.VOWEL.label()).isEqualTo("모음");
    assertThat(Prefix.SYLLABLE.label()).isEqualTo("음절");
  }
}
