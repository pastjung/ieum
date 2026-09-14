package com.itda.user.presentation;

import com.itda.user.application.AccessTokenRefreshCommand;
import com.itda.user.application.AccessTokenRefreshService;
import com.itda.user.application.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Access Token 갱신 API (API 명세 3.3·3.3.1절).
 *
 * <p>플랫폼 판정과 전달 위치 판정을 <b>가장 먼저</b> 한다. 어느 자리에서 Refresh Token을 읽을지 확정되지 않은 요청으로 JWT 검증·Redis 회전·DB
 * 조회를 시작하지 않는다.
 *
 * <p>갱신에는 삭제 쿠키를 붙이지 않는다. 계약이 삭제 쿠키를 정한 것은 로그아웃뿐이고, 갱신 실패로 브라우저의 기존 쿠키를 지우면 아직 유효할 수 있는 세션까지 잃는다.
 *
 * <p>{@code X-Request-Id}를 만들거나 덮어쓰지 않는다. 공통 필터가 모든 응답에 하나만 싣는다.
 */
@RestController
public class AccessTokenController {

  private final AccessTokenRefreshService accessTokenRefreshService;

  public AccessTokenController(AccessTokenRefreshService accessTokenRefreshService) {
    this.accessTokenRefreshService = accessTokenRefreshService;
  }

  /**
   * Refresh Token 회전과 Access Token 재발급. 성공은 로그인과 같은 {@code 201}과 인증 응답이다.
   *
   * <p>body는 필수가 아니다 — WEB은 쿠키로만 보내므로 body 자체를 싣지 않는다.
   */
  @PostMapping("/access-tokens")
  public ResponseEntity<?> refresh(
      HttpServletRequest httpRequest, @RequestBody(required = false) RefreshTokenRequest request) {
    ClientPlatform platform = ClientPlatform.from(httpRequest);
    RefreshTokenDelivery delivery = RefreshTokenDelivery.resolve(httpRequest, platform, request);

    AuthSession session =
        this.accessTokenRefreshService.refresh(
            new AccessTokenRefreshCommand(delivery.requireToken()));

    return platform == ClientPlatform.WEB
        ? AuthSessionResponses.web(session)
        : AuthSessionResponses.mobile(session);
  }
}
