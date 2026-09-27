package com.itda.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.user.service.UserWithdrawalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 회원 탈퇴 요청 DTO의 값 전달과 노출 범위를 고정한다 (API 명세 3.2.1절).
 *
 * <p>현재 비밀번호는 가공하지 않는다. 누락·빈 문자열 판정은 Service가 하므로 DTO는 받은 값을 그대로 넘긴다. 비문자열 JSON의 {@code 400}은 실제
 * HTTP 경로 테스트가 확인한다.
 */
class UserWithdrawalDtoTest {

  private static final long USER_ID = 42L;
  private static final String CURRENT_PASSWORD = "  앞뒤 공백까지 비밀번호인 문구  ";

  @Test
  void 현재_비밀번호를_가공하지_않고_인증된_사용자_식별자와_합친다() {
    UserWithdrawalService.Command command =
        new UserWithdrawalRequest(CURRENT_PASSWORD).toCommand(USER_ID);

    assertThat(command.userId()).isEqualTo(USER_ID);
    assertThat(command.currentPassword()).isEqualTo(CURRENT_PASSWORD);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void 누락과_공백_판정을_하지_않고_그대로_넘긴다(String value) {
    assertThat(new UserWithdrawalRequest(value).toCommand(USER_ID).currentPassword())
        .isEqualTo(value);
  }

  @Test
  void 요청과_Command의_toString은_값을_가린다() {
    UserWithdrawalRequest request = new UserWithdrawalRequest(CURRENT_PASSWORD);

    assertThat(request).hasToString("UserWithdrawalRequest[REDACTED]");
    assertThat(request.toCommand(USER_ID))
        .hasToString("UserWithdrawalCommand[REDACTED]")
        .asString()
        .doesNotContain(CURRENT_PASSWORD.strip())
        .doesNotContain(Long.toString(USER_ID));
  }
}
