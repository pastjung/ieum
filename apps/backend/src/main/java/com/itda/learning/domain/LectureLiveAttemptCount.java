package com.itda.learning.domain;

/**
 * 수업 id별 LIVE 풀이·정답 집계.
 *
 * <p>수업 목록 항목의 정답률(correctRate)이 이 값으로 만들어진다. 페이지에 실린 수업 id들로 한 번에 묶어 세고, 항목마다 다시 조회하지 않는다 (API 명세
 * 8.2절).
 */
public interface LectureLiveAttemptCount {

  Long getLectureId();

  long getTotalCount();

  long getCorrectCount();
}
