package com.itda.user.dto;

import com.itda.user.service.UserRegistrationService;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * {@code POST /api/v1/users} 요청 body (API 명세 3.2.1절).
 *
 * <p>필드가 정확히 셋이다. {@code role}·{@code status}·{@code emailVerified}·{@code level}·{@code
 * authVersion}·{@code rowVersion}을 받지 않으므로 클라이언트가 그 이름으로 값을 보내도 바인딩될 자리가 없다. 전역 unknown-property
 * 정책을 새로 만들지 않고 타입의 모양으로 막는다.
 *
 * <p>Bean Validation 애너테이션을 붙이지 않는다. 붙이면 실패가 {@code MethodArgumentNotValidException}으로 나가 공통 {@code
 * INVALID_REQUEST_INPUT}이 되는데, 이 API의 계약은 {@code 422 INVALID_ACCOUNT_INPUT}이다. 누락·형식 판정을 Service가 한
 * 자리에서 하도록 여기서는 원문만 옮긴다.
 *
 * <p>세 필드 모두 JSON 문자열만 받는다. 숫자·불리언을 문자열로 바꿔 받으면 타입 불일치가 형식 위반(422)으로 둔갑하는데, 계약은 그것을 {@code 400}으로
 * 정했다 ({@link JsonStringOnlyDeserializer}).
 *
 * <p>{@code toString()}을 가린다 — 비밀번호 원문과 개인정보가 들어 있다.
 */
public record UserRegistrationRequest(
    @JsonDeserialize(using = JsonStringOnlyDeserializer.class) String username,
    @JsonDeserialize(using = JsonStringOnlyDeserializer.class) String email,
    @JsonDeserialize(using = JsonStringOnlyDeserializer.class) String password) {

  public UserRegistrationService.Command toCommand() {
    return new UserRegistrationService.Command(this.username, this.email, this.password);
  }

  @Override
  public String toString() {
    return "UserRegistrationRequest[REDACTED]";
  }
}
