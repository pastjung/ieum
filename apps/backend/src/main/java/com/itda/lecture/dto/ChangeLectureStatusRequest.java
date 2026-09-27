package com.itda.lecture.dto;

import com.itda.lecture.domain.LectureStatus;
import jakarta.validation.constraints.NotNull;

/** OPEN 또는 CLOSED. SCHEDULED로 되돌리는 요청은 서비스가 409로 거부한다. */
public record ChangeLectureStatusRequest(@NotNull LectureStatus status) {}
