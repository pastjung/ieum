package com.itda.user.presentation;

import com.itda.user.application.AuthSessionRevocationCommand;
import com.itda.user.application.AuthSessionRevocationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그아웃 API — 현재 Refresh 계열 폐기 (API 명세 3.3·3.3.1·3.6.2절).
 *
 * <p><b>삭제 쿠키를 업무 처리보다 먼저 붙인다.</b> 계약은 "WEB으로 판정되고 전달 경로 충돌이 없는 요청에는 서버 판정이 {@code 204}·{@code
 * 401}·{@code 503} 중 무엇이든 삭제 {@code Set-Cookie}를 반환한다"고 정했다. 판정이 끝난 뒤에 붙이려 하면 {@code 401}·{@code
 * 503}은 예외로 빠져나가 공통 오류 처리가 응답을 만들고, 그 경로에는 쿠키를 붙일 자리가 없다. 그래서 플랫폼과 전달 경로가 확정된 직후 servlet 응답에 직접
 * 싣는다. {@code @ExceptionHandler}는 이미 추가된 헤더를 지우지 않으므로 오류 응답에도 그대로 남는다.
 *
 * <p>반대로 {@code 400 INVALID_CLIENT_PLATFORM}에는 붙지 않는다. 플랫폼 판정과 전달 경로 판정이 그 앞에서 끝나므로 쿠키를 붙이는 줄까지 오지
 * 못한다. WEB 여부가 확정되지 않은 요청의 쿠키를 건드리지 않는다는 계약과 같은 결과다.
 *
 * <p>MOBILE에는 쿠키를 붙이지 않는다. 로컬 보안 저장소 삭제는 클라이언트 책임이다.
 */
@RestController
public class AuthSessionRevocationController {

  private final AuthSessionRevocationService authSessionRevocationService;

  public AuthSessionRevocationController(
      AuthSessionRevocationService authSessionRevocationService) {
    this.authSessionRevocationService = authSessionRevocationService;
  }

  /** 성공은 {@code 204}와 완전히 빈 body다. */
  @PostMapping("/auth-session-revocations")
  public ResponseEntity<Void> revoke(
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse,
      @RequestBody(required = false) RefreshTokenRequest request) {
    ClientPlatform platform = ClientPlatform.from(httpRequest);
    RefreshTokenDelivery delivery = RefreshTokenDelivery.resolve(httpRequest, platform, request);
    if (platform == ClientPlatform.WEB) {
      // 여기서부터는 어떤 판정이 나와도 브라우저의 refresh 쿠키를 지운다.
      httpResponse.addHeader(HttpHeaders.SET_COOKIE, RefreshTokenCookie.delete());
    }

    this.authSessionRevocationService.revoke(
        new AuthSessionRevocationCommand(delivery.requireToken()));

    return ResponseEntity.noContent().build();
  }
}
