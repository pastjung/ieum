package com.itda.learning.domain;

import com.itda.global.braille.BraillePattern;

/**
 * 한 Cell의 채점 입력. Attempt를 만들 때 Cell 순서대로 넘긴다.
 *
 * <p>expectedPattern은 <b>서버가 problems의 정답에서 복사한 값</b>이다. ERD 5.2절이 "클라이언트 값을 받지 않음"으로 규정한다.
 * inputPattern만 사용자가 명시한 값이며 빈 점형은 0으로 온다 — 누락 Cell과 구분해야 한다.
 *
 * <p>diff_mask·is_correct는 이 두 값에서 서버가 계산하므로 여기에 담지 않는다.
 */
public record CellSubmission(BraillePattern expectedPattern, BraillePattern inputPattern) {

  public CellSubmission {
    if (expectedPattern == null || inputPattern == null) {
      throw new IllegalArgumentException("정답 패턴과 입력 패턴은 모두 필수입니다.");
    }
  }

  public static CellSubmission of(int expectedPattern, int inputPattern) {
    return new CellSubmission(BraillePattern.of(expectedPattern), BraillePattern.of(inputPattern));
  }
}
