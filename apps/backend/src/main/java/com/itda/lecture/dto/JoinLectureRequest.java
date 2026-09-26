package com.itda.lecture.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record JoinLectureRequest(@NotNull @Pattern(regexp = "\\d{6}") String participationCode) {}
