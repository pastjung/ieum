package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;

/**
 * LIVE 답안 채점 규칙 (API 명세 8.2.2절). 칸별 XOR만 본다 — LIVE는 AI 분석·BKT에서 빠진다 (8.1절).
 *
 * <p>칸 수는 입력·정답 중 긴 쪽이고 모자란 쪽은 0으로 채운다. attempt_cells의 두 패턴이 모두 NOT NULL이라 저장 모양과 응답 모양을 같게 둔다.
 */
final class LiveAnswerGrader {

  /** 개인 학습·AI 입력 상한(ProblemService.MAX_ASSIGNABLE_CELLS)과 같은 12칸. */
  static final int MAX_CELLS = 12;

  private static final int MAX_PATTERN = 0b111111;
  private static final int FULL_SCORE = 100;

  private LiveAnswerGrader() {}

  static void validate(List<Integer> cells) {
    if (cells == null || cells.isEmpty() || cells.size() > MAX_CELLS) {
      throw invalid();
    }
    for (Integer cell : cells) {
      if (cell == null || cell < 0 || cell > MAX_PATTERN) {
        throw invalid();
      }
    }
  }

  static List<AnswerCell> grade(List<Integer> input, List<Integer> expected) {
    int length = Math.max(input.size(), expected.size());
    List<AnswerCell> cells = new ArrayList<>(length);
    for (int i = 0; i < length; i++) {
      cells.add(new AnswerCell(i + 1, patternAt(input, i), patternAt(expected, i)));
    }
    return List.copyOf(cells);
  }

  /** 칸 수가 같고 모든 칸이 같아야 정답이다. */
  static boolean isCorrect(List<Integer> input, List<Integer> expected) {
    return input.equals(expected);
  }

  /** 정답이면 100, 아니면 diffMask가 0인 칸의 비율을 내림한다. 분모는 긴 쪽 칸 수다. */
  static int score(boolean correct, List<AnswerCell> cells) {
    if (correct) {
      return FULL_SCORE;
    }
    long matched = cells.stream().filter(AnswerCell::correct).count();
    return (int) (FULL_SCORE * matched / cells.size());
  }

  /**
   * 저장된 풀이와 같은 답인지. 저장된 입력은 0으로 채워져 있어 원래 길이를 모른다 — 제출값을 저장된 칸 수만큼 0으로 채워 비교하고, 저장된 칸보다 길면 다른 답으로
   * 본다.
   */
  static boolean sameInput(List<Integer> submitted, List<AnswerCell> stored) {
    if (submitted.size() > stored.size()) {
      return false;
    }
    for (int i = 0; i < stored.size(); i++) {
      if (patternAt(submitted, i) != stored.get(i).inputPattern()) {
        return false;
      }
    }
    return true;
  }

  private static int patternAt(List<Integer> patterns, int index) {
    return index < patterns.size() ? patterns.get(index) : 0;
  }

  private static BusinessException invalid() {
    return new BusinessException(ErrorCode.INVALID_ANSWER_CELLS);
  }
}
