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

  /**
   * 발급과 삭제가 공유하는 속성이다.
   *
   * <p>계약은 삭제 쿠키의 속성을 "발급과 동일"로 정했다. 브라우저는 이름·{@code Path}·{@code Domain}·{@code Secure}가 모두 같아야 같은
   * 쿠키로 보고 덮어쓰므로, 한쪽만 바뀌면 삭제가 조용히 실패하고 브라우저에 토큰이 남는다. 그래서 문자열을 한 곳에 둔다.
   */
  private static final String SHARED_ATTRIBUTES = "; Path=/; Secure; HttpOnly; SameSite=Strict";

  /** 삭제 쿠키의 {@code Expires}. 값 자체에 의미는 없고 이미 지난 시각이기만 하면 된다. */
  private static final Instant DELETED_EXPIRES_AT = Instant.EPOCH;

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
        + SHARED_ATTRIBUTES;
  }

  /**
   * 삭제용 {@code Set-Cookie} 값 (API 명세 3.3.1절 「WEB 쿠키」 표의 삭제 열).
   *
   * <p>빈 값에 {@code Max-Age=0}과 과거 {@code Expires}를 함께 싣는다. {@code Max-Age}만 이해하는 클라이언트와 {@code
   * Expires}만 이해하는 클라이언트가 모두 같은 결과를 내게 하기 위해서다.
   *
   * <p>이 값은 로그아웃 <b>판정 결과와 무관하게</b> 나간다 (3.3.1·3.6.2절). 서버 상태를 정리하지 못했더라도 브라우저에 토큰을 남길 이유가 없다.
   */
  static String delete() {
    return NAME
        + "=; Max-Age=0; Expires="
        + EXPIRES_FORMATTER.format(DELETED_EXPIRES_AT)
        + SHARED_ATTRIBUTES;
  }
}
