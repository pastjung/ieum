package com.itda.user.presentation;

import com.itda.user.application.AuthSession;

/**
 * MOBILE 인증 응답 body (API 명세 3.3.1절).
 *
 * <p>공통 네 필드에 {@code refreshToken}·{@code refreshTokenExpiresAt}을 더한다. 두 필드 모두 required·non-null이다.
 *
 * <p>WEB과 타입을 나눈 이유는 계약이 "구현은 WEB과 MOBILE의 응답 타입을 분리한다"고 정했기 때문이다. 한 타입에 담고 플랫폼별로 값을 비우면, 비우는 것을 잊은
 * 한 경로가 그대로 Refresh Token 유출이 된다.
 *
 * <p>네이티브 앱은 이 값을 Android Keystore·iOS Keychain과 동등한 OS 보안 저장소에만 보관한다. 그 저장은 클라이언트 책임이며 서버는 별도로
 * 보관하지 않는다.
 *
 * <p>{@code toString()}을 가린다 — Access·Refresh 원문과 개인정보가 들어 있다.
 */
public record MobileAuthSessionResponse(
    String accessToken,
    String tokenType,
    String accessTokenExpiresAt,
    String refreshToken,
    String refreshTokenExpiresAt,
    UserSummaryResponse user) {

  static MobileAuthSessionResponse from(AuthSession session) {
    return new MobileAuthSessionResponse(
        session.accessToken(),
        WebAuthSessionResponse.TOKEN_TYPE,
        Iso8601Utc.format(session.accessTokenExpiresAt()),
        session.refreshToken(),
        Iso8601Utc.format(session.refreshTokenExpiresAt()),
        UserSummaryResponse.from(session.user()));
  }

  @Override
  public String toString() {
    return "MobileAuthSessionResponse[REDACTED]";
  }
}
