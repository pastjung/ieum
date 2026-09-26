package com.itda.lecture.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** API 명세 8.2절. title은 DB VARCHAR(150) 범위. */
public record CreateLectureRequest(
    @NotBlank @Size(max = 150) String title, String description, Instant scheduledAt) {

  public LocalDateTime scheduledAtUtc() {
    return scheduledAt == null ? null : LocalDateTime.ofInstant(scheduledAt, ZoneOffset.UTC);
  }
}
