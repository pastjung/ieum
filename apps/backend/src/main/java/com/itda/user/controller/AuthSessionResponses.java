package com.itda.user.controller;

import com.itda.user.dto.MobileAuthSessionResponse;
import com.itda.user.dto.WebAuthSessionResponse;
import com.itda.user.service.AuthSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 플랫폼 중립 인증 결과를 HTTP 응답으로 옮긴다 (API 명세 3.3.1절).
 *
 * <p>로그인과 갱신이 <b>같은 인증 응답</b>을 쓴다(3.2절 표). 두 컨트롤러가 각자 조립하면 한쪽만 고쳐져 WEB body에 Refresh 필드가 새거나 쿠키 속성이
 * 갈라질 수 있으므로 조립을 한 곳에 둔다.
 */
final class AuthSessionResponses {

  private AuthSessionResponses() {}

  /** Refresh Token은 body가 아니라 {@code __Host-refreshToken} 쿠키로만 나간다. */
  static ResponseEntity<WebAuthSessionResponse> web(AuthSession session) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(
            HttpHeaders.SET_COOKIE,
            RefreshTokenCookie.issue(session.refreshToken(), session.refreshTokenExpiresAt()))
        .body(WebAuthSessionResponse.from(session));
  }

  /** 쿠키를 설정하지 않는다. 네이티브 앱에는 쿠키 저장소가 없고, 있어도 OS 보안 저장소가 아니다. */
  static ResponseEntity<MobileAuthSessionResponse> mobile(AuthSession session) {
    return ResponseEntity.status(HttpStatus.CREATED).body(MobileAuthSessionResponse.from(session));
  }
}
