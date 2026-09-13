package com.itda.user.presentation;

import com.itda.user.application.EmailVerificationRequestService;
import com.itda.user.application.EmailVerificationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이메일 인증 요청·완료 API (API 명세 3.4.1절).
 *
 * <p>두 endpoint를 한 클래스에 둔다. 같은 기능의 요청과 완료라 계약이 함께 움직이며, 한쪽만 고쳐져 상태 코드나 body 규칙이 갈라지는 것을 막는다.
 *
 * <p>클래스 단위 경로 접두사를 두지 않는다. 두 resource가 형제 경로({@code /email-verification-requests}, {@code
 * /email-verifications})라 공통 접두사가 없다. {@code /api/v1}은 servlet context-path가 붙인다.
 *
 * <p><b>{@code X-Client-Platform}을 읽지 않고 쿠키를 읽지도 설정하지도 않는다.</b> WEB과 MOBILE이 같은 body·상태 코드·오류를 쓴다.
 * 플랫폼 분기는 인증 세션 API에만 있다. Access·Refresh Token도 발급하지 않는다.
 *
 * <p>두 성공 응답 모두 body가 없다. 빈 객체 {@code {}}나 {@code {"success": true}}를 보내지 않는다.
 */
@RestController
public class EmailVerificationController {

  private final EmailVerificationRequestService emailVerificationRequestService;
  private final EmailVerificationService emailVerificationService;

  public EmailVerificationController(
      EmailVerificationRequestService emailVerificationRequestService,
      EmailVerificationService emailVerificationService) {
    this.emailVerificationRequestService = emailVerificationRequestService;
    this.emailVerificationService = emailVerificationService;
  }

  /**
   * 이메일 인증 메일 요청·재발송.
   *
   * <p>{@code 202}는 메일 작업의 내구적 접수만 뜻한다. 계정이 없거나 이미 인증됐거나 탈퇴한 경우에도 응답이 달라지지 않는다.
   */
  @PostMapping("/email-verification-requests")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void request(@RequestBody EmailVerificationMailRequest request) {
    this.emailVerificationRequestService.request(request.rawEmail());
  }

  /** 이메일 인증 완료. 성공은 {@code 204}이며 자동 로그인하지 않는다. */
  @PostMapping("/email-verifications")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void verify(@RequestBody EmailVerificationCompletionRequest request) {
    this.emailVerificationService.complete(request.rawToken());
  }
}
