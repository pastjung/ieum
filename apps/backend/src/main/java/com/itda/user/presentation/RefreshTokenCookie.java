package com.itda.user.presentation;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * WEB Refresh Token 쿠키 (API 명세 3.3.1절 「WEB 쿠키」 표).
 *
 * <p>이름이 {@code __Host-} 접두사를 쓰므로 브라우저가 {@code Secure}·{@code Path=/}·{@code Domain} 생략을 강제한다. 이
 * 조합이라야 하위 도메인에서 쿠키를 덮어쓸 수 없다.
 *
 * <p><b>{@code Set-Cookie}를 직접 만든다.</b> Spring의 {@code ResponseCookie}는 {@code Expires}를 직렬화 시점의 현재
 * 시각 + {@code Max-Age}로 스스로 계산한다. 계약은 {@code Expires}가 <b>Refresh Token의 실제 만료 시각</b>과 같아야 한다고
 * 정했으므로, 그 값을 그대로 쓰려면 헤더를 직접 조립해야 한다.
 *
 * <p>값은 compact JWS(Base64URL과 {@code .})라 쿠키 값에 바로 쓸 수 있는 문자만 포함한다.
 */
final class RefreshTokenCookie {

  static final String NAME = "__Host-refreshToken";

  /** 계약이 고정한 값: 14일. Refresh Token 유효기간과 같다. */
  static final long MAX_AGE_SECONDS = Duration.ofDays(14).toSeconds();

  /** {@code Expires}는 RFC 1123 GMT 표기다. Locale에 따라 요일·월 약어가 바뀌지 않도록 ROOT로 고정한다. */
  private static final DateTimeFormatter EXPIRES_FORMATTER =
      DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);

  private RefreshTokenCookie() {}

  /**
   * 발급용 {@code Set-Cookie} 값.
   *
   * @param expiresAt Refresh Token의 실제 만료 시각(UTC). {@code Expires}에 그대로 쓴다
   */
  static String issue(String refreshToken, LocalDateTime expiresAt) {
    return NAME
        + "="
        + refreshToken
        + "; Max-Age="
        + MAX_AGE_SECONDS
        + "; Expires="
        + EXPIRES_FORMATTER.format(Instant.ofEpochSecond(expiresAt.toEpochSecond(ZoneOffset.UTC)))
        + "; Path=/; Secure; HttpOnly; SameSite=Strict";
  }
}
