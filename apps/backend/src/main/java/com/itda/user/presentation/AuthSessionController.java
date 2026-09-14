package com.itda.user.presentation;

import com.itda.user.application.AuthSession;
import com.itda.user.application.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 세션 API (API 명세 3.3·3.3.1절).
 *
 * <p><b>플랫폼 분기가 여기에만 있다.</b> {@link LoginService}는 플랫폼 중립 결과를 돌려주고, 그 결과를 WEB 쿠키로 보낼지 MOBILE body에
 * 실을지는 이 계층이 정한다. 전달 방식이 바뀌어도 인증 판정은 그대로 둘 수 있어야 한다.
 *
 * <p>플랫폼 판정을 <b>가장 먼저</b> 한다. 어느 방식으로 응답할지 확정되지 않은 요청으로는 DB 조회·Argon2id·JWT 발급·Redis 변경을 시작하지 않는다.
 *
 * <p>{@code X-Request-Id}를 만들거나 덮어쓰지 않는다. 공통 필터가 모든 응답에 하나만 싣는다.
 */
@RestController
public class AuthSessionController {

  private final LoginService loginService;

  public AuthSessionController(LoginService loginService) {
    this.loginService = loginService;
  }

  /**
   * 로그인.
   *
   * <p>성공은 {@code 201}이다. 오류 응답에는 Refresh 쿠키를 설정하지 않는다 — 실패한 요청이 브라우저의 기존 쿠키를 건드릴 이유가 없다.
   */
  @PostMapping("/auth-sessions")
  public ResponseEntity<?> login(
      HttpServletRequest httpRequest, @RequestBody LoginRequest request) {
    ClientPlatform platform = ClientPlatform.from(httpRequest);

    AuthSession session = this.loginService.login(request.toCommand());

    // 응답 조립은 갱신 API와 공유한다. 계약이 두 API에 같은 인증 응답을 쓰기 때문이다.
    return platform == ClientPlatform.WEB
        ? AuthSessionResponses.web(session)
        : AuthSessionResponses.mobile(session);
  }
}
