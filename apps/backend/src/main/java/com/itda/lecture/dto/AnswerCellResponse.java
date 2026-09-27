package com.itda.lecture.dto;

import com.itda.lecture.service.AnswerCell;

public record AnswerCellResponse(
    int cellIndex, int inputPattern, int expectedPattern, int diffMask, boolean isCorrect) {

  public static AnswerCellResponse from(AnswerCell cell) {
    return new AnswerCellResponse(
        cell.cellIndex(),
        cell.inputPattern(),
        cell.expectedPattern(),
        cell.diffMask(),
        cell.correct());
  }
}
