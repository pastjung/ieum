package com.itda.user.presentation;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 응답에 싣는 시각 표기 (API 명세 3.3.1절: ISO 8601 UTC).
 *
 * <p>저장·전달 값이 UTC {@code LocalDateTime}이라 시간대 정보가 없으므로 여기서 UTC로 못박아 직렬화한다. 서버 기본 시간대에 맡기면 배포 환경에 따라
 * 값이 달라진다.
 */
final class Iso8601Utc {

  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

  private Iso8601Utc() {}

  static String format(LocalDateTime utc) {
    return utc.atOffset(ZoneOffset.UTC).format(FORMATTER);
  }
}
