package com.itda.learning.domain;

import java.math.BigDecimal;

/**
 * 숙련 구간 (ERD 5.4절). DB 컬럼이 아니라 mastery_probability에서 파생한다.
 *
 * <p>"행 없음 = 미관측"은 이 enum의 값이 아니다. user_skill_memory 행 자체가 없는 상태이므로 조회 결과의 Optional 부재로 표현한다.
 */
public enum MasteryBand {
  /** 0 이상 0.70 미만 */
  WEAK,
  /** 0.70 이상 0.95 미만 */
  LEARNING,
  /** 0.95 이상 1 이하 */
  MASTERED;

  /** ERD 4.7절: 다음 단계 진급 기준이기도 하다. */
  public static final BigDecimal MASTERED_THRESHOLD = new BigDecimal("0.95");

  public static final BigDecimal LEARNING_THRESHOLD = new BigDecimal("0.70");

  public static MasteryBand of(BigDecimal masteryProbability) {
    if (masteryProbability == null) {
      throw new IllegalArgumentException("숙련 확률은 필수입니다.");
    }
    if (masteryProbability.compareTo(MASTERED_THRESHOLD) >= 0) {
      return MASTERED;
    }
    return masteryProbability.compareTo(LEARNING_THRESHOLD) >= 0 ? LEARNING : WEAK;
  }
}
