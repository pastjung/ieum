package com.itda.user.controller;

import com.itda.user.dto.UsernameAvailabilityResponse;
import com.itda.user.service.UsernameAvailabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 아이디 사용 가능 확인 (API 명세 3.2.1절). */
@RestController
public class UsernameAvailabilityController {

  private final UsernameAvailabilityService usernameAvailabilityService;

  public UsernameAvailabilityController(UsernameAvailabilityService usernameAvailabilityService) {
    this.usernameAvailabilityService = usernameAvailabilityService;
  }

  /**
   * 아이디를 가입에 쓸 수 있는지 조회한다. 인증을 요구하지 않는다 — 가입 화면이 로그인 전에 부른다.
   *
   * <p>{@code required = false}로 받는다. Spring 기본값({@code true})은 누락을 {@code
   * MissingServletRequestParameterException}으로 만들어 공통 {@code 400}으로 내보내는데, 이 API의 계약은 누락도 {@code
   * 422 INVALID_ACCOUNT_INPUT}이다. 누락·형식 판정을 Service가 한 자리에서 하도록 여기서는 원문만 넘긴다.
   */
  @GetMapping("/username-availability")
  public UsernameAvailabilityResponse check(
      @RequestParam(name = "username", required = false) String username) {
    UsernameAvailabilityService.Result result = this.usernameAvailabilityService.check(username);
    return new UsernameAvailabilityResponse(result.username(), result.available());
  }
}
