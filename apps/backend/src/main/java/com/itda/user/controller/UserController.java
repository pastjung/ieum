package com.itda.user.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.user.dto.UserLevelRequest;
import com.itda.user.dto.UserRegistrationRequest;
import com.itda.user.dto.UserSummaryResponse;
import com.itda.user.dto.UserWithdrawalRequest;
import com.itda.user.service.UserLevelService;
import com.itda.user.service.UserRegistrationService;
import com.itda.user.service.UserSummary;
import com.itda.user.service.UserSummaryQueryService;
import com.itda.user.service.UserWithdrawalService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계정 리소스 API (API 명세 3.2·3.2.1절).
 *
 * <p>요청·응답 변환만 한다. 판정과 오류 분류는 Service가 하고 여기서는 결과를 HTTP로 옮긴다.
 *
 * <p>성공 응답에 공통 wrapper를 씌우지 않는다. 계약이 사용자 요약을 그대로 돌려주도록 정했고, 이 저장소의 공통 응답 규약도 성공은 본문을 그대로 둔다.
 */
@RestController
@RequestMapping("/users")
public class UserController {

  private final UserRegistrationService userRegistrationService;
  private final UserSummaryQueryService userSummaryQueryService;
  private final UserWithdrawalService userWithdrawalService;
  private final UserLevelService userLevelService;

  public UserController(
      UserRegistrationService userRegistrationService,
      UserSummaryQueryService userSummaryQueryService,
      UserWithdrawalService userWithdrawalService,
      UserLevelService userLevelService) {
    this.userRegistrationService = userRegistrationService;
    this.userSummaryQueryService = userSummaryQueryService;
    this.userWithdrawalService = userWithdrawalService;
    this.userLevelService = userLevelService;
  }

  /**
   * 회원가입.
   *
   * <p>{@code Location} 헤더를 두지 않는다 — 생성된 계정의 전용 조회 URI가 계약에 없다({@code /users/{id}}를 만들지 않는다).
   * Access·Refresh Token과 쿠키도 없다. 이메일 인증 전에는 토큰을 발급하지 않는 것이 계약이다.
   */
  @PostMapping
  public ResponseEntity<UserSummaryResponse> register(
      @RequestBody UserRegistrationRequest request) {
    UserSummary summary = this.userRegistrationService.register(request.toCommand());
    return ResponseEntity.status(HttpStatus.CREATED).body(UserSummaryResponse.from(summary));
  }

  /**
   * 내 정보.
   *
   * <p>사용자 식별자를 path·query·임의 헤더에서 받지 않는다. 요청이 고른 값으로 남의 정보를 읽을 수 있게 되기 때문이며, 근거는 인증 필터가 확인한
   * principal뿐이다.
   */
  @GetMapping("/me")
  public UserSummaryResponse me(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
    return UserSummaryResponse.from(
        this.userSummaryQueryService.getSummary(authenticatedUser.userId()));
  }

  /**
   * 학습 단계 설정. 성공은 {@code 200}과 갱신된 사용자 요약이다.
   *
   * <p>요약을 돌려주는 이유: 앱이 홈에서 이 값을 배지로 보여주고 캐시에 들고 있다. 빈 응답을 주면 곧바로 {@code GET /users/me}를 한 번 더
   * 부르게 된다.
   *
   * <p>단일 값 하위 리소스의 교체라 {@code PUT}이다 — 같은 값을 두 번 보내도 결과가 같다. 대상은 인증 필터가 확인한 principal뿐이고
   * body·path·query의 사용자 식별자를 받지 않는다.
   */
  @PutMapping("/me/level")
  public UserSummaryResponse changeLevel(
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
      @Valid @RequestBody UserLevelRequest request) {
    this.userLevelService.changeLevel(authenticatedUser.userId(), request.level());
    return UserSummaryResponse.from(
        this.userSummaryQueryService.getSummary(authenticatedUser.userId()));
  }

  /**
   * 회원 탈퇴. 성공은 {@code 204}와 빈 body다.
   *
   * <p>대상은 인증 필터가 확인한 principal뿐이다. body·path·query의 사용자 식별자를 받지 않는다. {@code X-Client-Platform}을 읽지
   * 않고 쿠키를 읽거나 지우지 않으며 토큰을 발급하지 않는다 — 클라이언트의 로컬 저장소 정리는 클라이언트 책임이다.
   */
  @PostMapping("/me/withdrawals")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void withdraw(
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
      @RequestBody UserWithdrawalRequest request) {
    this.userWithdrawalService.withdraw(request.toCommand(authenticatedUser.userId()));
  }
}
