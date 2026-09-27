package com.itda.lecture.service;

import com.itda.learning.domain.AttemptCell;

/** 채점한 한 칸. cellIndex는 1부터, 모자란 쪽 패턴은 0이다 (API 명세 8.2.2절). */
public record AnswerCell(int cellIndex, int inputPattern, int expectedPattern) {

  public int diffMask() {
    return inputPattern ^ expectedPattern;
  }

  public boolean correct() {
    return diffMask() == 0;
  }

  static AnswerCell from(AttemptCell cell) {
    return new AnswerCell(cell.getCellIndex(), cell.getInputPattern(), cell.getExpectedPattern());
  }
}
