package com.itda.lecture.domain;

/**
 * 수업 id별 건수 집계.
 *
 * <p>수업 목록이 항목마다 연결 문제 수·참여자 수를 보여준다. 페이지에 실린 수업 id들로 한 번에 묶어 세고, 항목마다 다시 조회하지 않는다 (API 명세 8.2절).
 */
public interface LectureIdCount {

  Long getLectureId();

  long getCount();
}
