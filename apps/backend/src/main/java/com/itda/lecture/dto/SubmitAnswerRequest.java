package com.itda.lecture.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

/**
 * LIVE 답안 body (API 명세 8.2.2절).
 *
 * <p>cells는 여기서 검증하지 않는다 — 업무 code {@code INVALID_ANSWER_CELLS}(422)로 답해야 하므로
 * 서비스(LiveAnswerGrader)가 검사한다. durationMs는 0 이상인지만 보고 저장하지 않는다.
 */
public record SubmitAnswerRequest(List<Integer> cells, @NotNull @PositiveOrZero Long durationMs) {}
