package com.itda.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

/** 외부 code 형식은 docs/conventions/code/api.md "에러 응답"을 따른다. */
class ErrorCodeTest {

  private static final String UPPER_SNAKE_CASE = "[A-Z][A-Z0-9]*(_[A-Z0-9]+)*";

  /** AUTH_4010·SYS_5000처럼 끝의 숫자 segment가 자기 HTTP 상태로 시작하면 상태를 되풀이한 이름이다. 그 밖의 숫자는 허용한다. */
  static boolean repeatsHttpStatus(String code, int status) {
    return code.matches(".*_" + status + "\\d*");
  }

  @ParameterizedTest
  @EnumSource(ErrorCode.class)
  void 외부_code는_enum_이름이다(ErrorCode errorCode) {
    assertThat(errorCode.getCode()).isEqualTo(errorCode.name());
  }

  @ParameterizedTest
  @EnumSource(ErrorCode.class)
  void code는_UPPER_SNAKE_CASE이고_HTTP_상태를_숫자로_되풀이하지_않는다(ErrorCode errorCode) {
    assertThat(errorCode.getCode()).matches(UPPER_SNAKE_CASE);
    assertThat(repeatsHttpStatus(errorCode.getCode(), errorCode.getStatus().value())).isFalse();
  }

  @ParameterizedTest
  @CsvSource({"AUTH_4010, 401", "SYS_5000, 500", "BRAILLE_4001, 400", "USER_4040, 404"})
  void 상태를_되풀이하는_숫자_suffix는_규칙_위반으로_판정한다(String code, int status) {
    assertThat(repeatsHttpStatus(code, status)).isTrue();
  }

  @ParameterizedTest
  @CsvSource({"BKT_V1, 200", "CURRICULUM_L1_LOCKED, 403", "PATTERN_OUT_OF_RANGE, 400"})
  void 상태와_무관한_숫자는_허용한다(String code, int status) {
    assertThat(repeatsHttpStatus(code, status)).isFalse();
  }

  @Test
  void 점자_패턴_범위_위반은_기존_상태와_message를_유지한다() {
    assertThat(ErrorCode.PATTERN_OUT_OF_RANGE.getStatus().value()).isEqualTo(400);
    assertThat(ErrorCode.PATTERN_OUT_OF_RANGE.getMessage()).isEqualTo("패턴 값이 허용 범위(0~63)를 벗어났습니다.");
  }

  @Test
  void 음성_합성_오류는_API_명세_12절의_상태를_쓴다() {
    assertThat(ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE.getStatus())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(ErrorCode.VOICE_RATE_LIMITED.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(ErrorCode.VOICE_SYNTHESIS_FAILED.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
  }
}
