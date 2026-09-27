package com.itda.lecture.dto;

import jakarta.validation.constraints.NotNull;

/** true면 발언 허용, false면 회수 (API 명세 8.5.1절). */
public record ChangeSpeakingRequest(@NotNull Boolean allowed) {}
