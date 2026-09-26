package com.itda.learning.domain;

/**
 * 개인 학습 세션 상태 (ERD 5.5절).
 *
 * <p>DB 컬럼이 아니다. completed_at·analysis_applied_at 두 시각에서 파생한다 — ERD가 "status·current_item_no
 * ·paused_at·updated_at 컬럼은 추가하지 않는다"고 명시한다.
 */
public enum SessionStatus {
  /** 생성·학습 미완료. 두 시각이 모두 NULL이다. */
  IN_PROGRESS,
  /** 학습 완료·분석 결과 미반영. completed_at만 존재한다. */
  ANALYSIS_PENDING,
  /** 학습 완료·분석 결과 반영 완료. 두 시각이 모두 존재한다. */
  ANALYZED
}
