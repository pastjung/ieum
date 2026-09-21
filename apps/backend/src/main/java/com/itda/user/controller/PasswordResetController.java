package com.itda.user.controller;

import com.itda.user.dto.PasswordResetCompletionRequest;
import com.itda.user.dto.PasswordResetMailRequest;
import com.itda.user.service.PasswordResetRequestService;
import com.itda.user.service.PasswordResetService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비밀번호 재설정 요청·완료 API (API 명세 3.4.2절).
 *
 * <p>두 endpoint를 한 클래스에 둔다. 같은 기능의 요청과 완료라 계약이 함께 움직인다. {@code /api/v1}은 servlet context-path가 붙인다.
 *
 * <p><b>{@code X-Client-Platform}을 읽지 않고 쿠키를 읽지도 설정하지도 않는다.</b> WEB과 MOBILE이 같은 body·상태 코드·오류를 쓴다.
 * 성공해도 Access·Refresh Token을 발급하지 않으며 자동 로그인하지 않는다.
 *
 * <p>두 성공 응답 모두 body가 없다. 빈 객체 {@code {}}나 {@code {"success": true}}를 보내지 않는다.
 */
@RestController
public class PasswordResetController {

  private final PasswordResetRequestService passwordResetRequestService;
  private final PasswordResetService passwordResetService;

  public PasswordResetController(
      PasswordResetRequestService passwordResetRequestService,
      PasswordResetService passwordResetService) {
    this.passwordResetRequestService = passwordResetRequestService;
    this.passwordResetService = passwordResetService;
  }

  /**
   * 비밀번호 재설정 메일 요청.
   *
   * <p>{@code 202}는 메일 작업의 내구적 접수만 뜻한다. 계정이 없거나 탈퇴한 경우에도 응답이 달라지지 않는다.
   */
  @PostMapping("/password-reset-requests")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void request(@RequestBody PasswordResetMailRequest request) {
    this.passwordResetRequestService.request(request.rawEmail());
  }

  /** 비밀번호 재설정 완료. 성공은 {@code 204}이며 사용자는 새 비밀번호로 직접 다시 로그인한다. */
  @PostMapping("/password-resets")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset(@RequestBody PasswordResetCompletionRequest request) {
    this.passwordResetService.complete(request.toCommand());
  }
}
