package com.itda.problem.domain;

/**
 * 문제 형태 (ERD 4.3절). 학습 요소의 skill_type과는 다른 개념이다.
 *
 * <p>SENTENCE 값이 존재한다는 것이 L8 문장 학습을 MVP에 포함한다는 뜻은 아니다.
 */
public enum ProblemType {
  /** 점 위치 문제. 정확히 1 Cell이다. */
  DOT_POSITION,
  /** 점 조합 문제. 정확히 1 Cell이다. */
  DOT_PATTERN,
  JAMO,
  SYLLABLE,
  WORD,
  SENTENCE;

  /** ERD 4.3·4.4절: 점 위치·조합 문제는 정확히 1 Cell이어야 한다. */
  public boolean requiresSingleCell() {
    return this == DOT_POSITION || this == DOT_PATTERN;
  }
}
