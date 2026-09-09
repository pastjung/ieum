package com.itda.learning.domain;

/**
 * learning_sessions.assigned_problems JSON 배열의 한 항목 (ERD 5.5절).
 *
 * <p>각 객체는 두 키만 가진다. 추가 키·스키마 버전·문제 전문 스냅샷은 저장하지 않는다.
 *
 * @param itemNo 배정 순번 (현재 1~10)
 * @param problemId problems.id의 <b>10진수 문자열</b>. ERD가 문자열로 규정했다 — JSON 안의 값에는 물리 FK가 걸리지 않으며 문제 존재와
 *     단계 소속은 백엔드가 확인한다
 */
public record AssignedProblem(int itemNo, String problemId) {

  public AssignedProblem {
    if (itemNo < 1) {
      throw new IllegalArgumentException("배정 순번은 1 이상이어야 합니다: " + itemNo);
    }
    if (problemId == null || !problemId.matches("\\d+")) {
      throw new IllegalArgumentException("problemId는 10진수 문자열이어야 합니다: " + problemId);
    }
  }

  public static AssignedProblem of(int itemNo, long problemId) {
    return new AssignedProblem(itemNo, Long.toString(problemId));
  }
}
