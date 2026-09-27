package com.itda.lecture.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * @param problemId 10진수 문자열 ID (API 명세 8.2절). long 범위를 넘지 않게 18자리까지 받는다.
 */
public record DeliverProblemRequest(
    @NotNull @Pattern(regexp = "[1-9][0-9]{0,17}") String problemId) {

  public long problemIdValue() {
    return Long.parseLong(problemId);
  }
}
