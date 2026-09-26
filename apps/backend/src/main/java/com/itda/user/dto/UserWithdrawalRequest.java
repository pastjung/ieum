package com.itda.user.dto;

import com.itda.user.service.UserWithdrawalService;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * {@code POST /api/v1/users/me/withdrawals} 요청 body (API 명세 3.2.1절).
 *
 * <p>{@code currentPassword} 하나만 받는다. 사용자 식별자를 body에서 받지 않는다 — 대상은 인증 필터가 확인한 principal뿐이며, body에
 * 다른 필드가 와도 이 타입에 자리가 없어 무시된다.
 *
 * <p>JSON 문자열만 받는다. 숫자·불리언을 문자열로 바꿔 주면 타입 불일치가 비밀번호 비교로 둔갑하므로, 회원가입·로그인과 같은 문자열 전용 deserializer로 읽기
 * 자체를 실패시켜 공통 {@code 400 MALFORMED_REQUEST_BODY}로 보낸다. 누락·{@code null}·빈 문자열 판정은 Service가 한다.
 *
 * <p>Bean Validation 애너테이션을 붙이지 않는다. 붙이면 실패가 공통 {@code INVALID_REQUEST_INPUT}으로 나가는데 이 API의 계약은
 * {@code INVALID_ACCOUNT_INPUT}이다.
 *
 * <p>{@code toString()}을 가린다 — 현재 비밀번호 원문이 들어 있다.
 */
public record UserWithdrawalRequest(
    @JsonDeserialize(using = JsonStringOnlyDeserializer.class) String currentPassword) {

  /** 인증된 사용자 식별자와 합쳐 Command를 만든다. 값을 가공하지 않는다. */
  public UserWithdrawalService.Command toCommand(long authenticatedUserId) {
    return new UserWithdrawalService.Command(authenticatedUserId, this.currentPassword);
  }

  @Override
  public String toString() {
    return "UserWithdrawalRequest[REDACTED]";
  }
}
