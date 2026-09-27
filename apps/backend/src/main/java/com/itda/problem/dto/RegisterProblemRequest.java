package com.itda.problem.dto;

import jakarta.validation.constraints.NotBlank;

/** 강사 입력 그대로의 글자. 구분어 해석은 ProblemTextQuery가 한다 (API 명세 8.2.1절). AI 분석 요청 body로도 쓴다. */
public record RegisterProblemRequest(@NotBlank String targetText) {}
