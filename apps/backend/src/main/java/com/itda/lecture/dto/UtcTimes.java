package com.itda.lecture.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 응답 시각 형식. user.dto.Iso8601Utc와 같은 형식이다 (API 명세: ISO 8601 UTC). */
final class UtcTimes {

  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

  private UtcTimes() {}

  static String format(LocalDateTime utc) {
    return utc == null ? null : utc.atOffset(ZoneOffset.UTC).format(FORMATTER);
  }
}
