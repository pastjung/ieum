package com.itda.problem.service;

import com.itda.problem.domain.Level;
import java.util.List;

/**
 * 수업에 연결된 문제 하나 (API 명세 8.2.1절 LinkedProblem). 강사에게는 정답 점형까지, 학습자에게는 그 일부만 내보낸다 — 가르는 것은 DTO다.
 *
 * @param problemType problems.level
 * @param expectedPattern cell_index 순서의 정답 점형(6비트)
 */
public record LinkedProblem(
    long problemId,
    String targetText,
    String displayText,
    Level problemType,
    List<Integer> expectedPattern) {

  public int cellCount() {
    return expectedPattern.size();
  }
}
