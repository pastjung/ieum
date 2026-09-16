package com.itda.user.dto;

import com.itda.user.service.AuthSession;

/**
 * WEB 인증 응답 body (API 명세 3.3.1절).
 *
 * <p><b>Refresh 관련 필드가 타입에 없다.</b> 계약이 "WEB body에는 어떤 경우에도 포함하지 않는다"고 정했으므로, 값을 비우는 대신 자리 자체를 두지
 * 않는다. 필드가 있으면 언젠가 {@code null}이나 실수로 채워진 값이 나갈 수 있고 그 순간 Refresh Token이 스크립트가 읽을 수 있는 자리에 놓인다.
 *
 * <p>Refresh Token은 {@code __Host-refreshToken} 쿠키로만 전달한다 ({@code RefreshTokenCookie}).
 *
 * <p>{@code toString()}을 가린다 — Access 원문과 개인정보가 들어 있다.
 */
public record WebAuthSessionResponse(
    String accessToken, String tokenType, String accessTokenExpiresAt, UserSummaryResponse user) {

  /** 계약이 고정한 값이다. 클라이언트가 이 문자열로 Authorization 헤더를 만든다. */
  static final String TOKEN_TYPE = "Bearer";

  public static WebAuthSessionResponse from(AuthSession session) {
    return new WebAuthSessionResponse(
        session.accessToken(),
        TOKEN_TYPE,
        Iso8601Utc.format(session.accessTokenExpiresAt()),
        UserSummaryResponse.from(session.user()));
  }

  @Override
  public String toString() {
    return "WebAuthSessionResponse[REDACTED]";
  }
}
