package com.itda.problem.dto;

import java.util.List;

/**
 * FastAPI {@code POST /braille-lookup} 응답. cells 는 칸마다 점형(0~63)과 요소 이름. 띄어쓰기 칸은 pattern 0, 요소 없음
 */
public record BrailleLookupFastApiResponse(String text, String braille, List<Cell> cells) {

  public record Cell(int cellIndex, int pattern, Long learningSkillId, String label) {}
}
