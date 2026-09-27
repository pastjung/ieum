package com.itda.problem.service;

import com.itda.problem.domain.Level;
import com.itda.problem.domain.SkillType;
import com.itda.problem.service.ProblemTextQuery.Prefix;
import java.util.Set;

/**
 * 문제의 표시 문구 (API 명세 8.2.1절). 같은 글자라도 초성·받침·모음·약자는 구분어를 붙여야 학습자가 무엇을 입력할지 안다.
 *
 * <p>입력 문자열이 아니라 문제의 level과 연결된 학습 요소로 정한다 — 같은 문제는 누가 어떻게 입력했든 같은 문구다.
 */
public final class ProblemDisplay {

  private ProblemDisplay() {}

  public static String displayText(Level level, String targetText, Set<SkillType> skillTypes) {
    return switch (level) {
      case CONSONANT -> {
        if (skillTypes.contains(SkillType.SKILL_01)) {
          yield prefixed(Prefix.ONSET, targetText);
        }
        if (skillTypes.contains(SkillType.SKILL_03)) {
          yield prefixed(Prefix.CODA, targetText);
        }
        yield targetText;
      }
      case VOWEL -> prefixed(Prefix.VOWEL, targetText);
      case CONTRACTION -> prefixed(Prefix.SYLLABLE, targetText);
      case WORD, SENTENCE, CONTRACTION_WORD -> targetText;
    };
  }

  private static String prefixed(Prefix prefix, String targetText) {
    return prefix.label() + " " + targetText;
  }
}
