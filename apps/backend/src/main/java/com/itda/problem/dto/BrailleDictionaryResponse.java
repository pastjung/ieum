package com.itda.problem.dto;

import java.util.List;

/**
 * 점자 사전 조회 결과.
 *
 * <p>{@code source}: {@code PROBLEM} 이면 등록된 문제의 정답 칸(problem_cells)을 그대로 돌려준 것이고, {@code CONVERTED}
 * 면 AI 규칙 점역기로 바꾼 것이다. 등록된 문제에서 온 칸은 요소 이름(label)이 없다 — problem_cells 가 칸의 요소를 저장하지 않기 때문이다.
 */
public record BrailleDictionaryResponse(
    String word, String source, String braille, List<Cell> cells) {

  public BrailleDictionaryResponse {
    cells = List.copyOf(cells);
  }

  /** cellIndex 는 1부터. label 은 "약자 바", "첫소리 ㅂ", "띄어쓰기" — 없으면 null */
  public record Cell(int cellIndex, int pattern, Long learningSkillId, String label) {}
}
