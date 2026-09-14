package com.itda.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 이메일 인증 두 요청 DTO의 값 판정과 노출 범위를 고정한다 (API 명세 3.4.1절).
 *
 * <p>HTTP 테스트가 상태 코드를 고정한다면 여기서는 그 근거가 되는 <b>한 자리 판정</b>을 고정한다 — JSON 문자열만 값으로 인정하고, 나머지는 전부 같은
 * {@code null}로 접는다.
 */
class EmailVerificationDtoTest {

  private static final String EMAIL = "learner@example.com";
  private static final String SAMPLE_COMPACT_VALUE = "header.payload.signature";

  /** 계약이 "문자열이 아니면 422"라고 정한 모든 JSON 모양. */
  static List<Arguments> nonStringValues() {
    return List.of(
        Arguments.of(123),
        Arguments.of(1.5),
        Arguments.of(true),
        Arguments.of(Map.of("value", EMAIL)),
        Arguments.of(List.of(EMAIL)));
  }

  // ---------- 값 판정 ----------

  @Test
  void 문자열이면_그대로_돌려준다() {
    assertThat(new EmailVerificationMailRequest(EMAIL).rawEmail()).isEqualTo(EMAIL);
    assertThat(new EmailVerificationCompletionRequest(SAMPLE_COMPACT_VALUE).rawToken())
        .isEqualTo(SAMPLE_COMPACT_VALUE);
  }

  @ParameterizedTest
  @MethodSource("nonStringValues")
  void 문자열이_아니면_null로_접는다(Object value) {
    // 누락·null·비문자열이 모두 같은 422라 여기서 구분하지 않는다.
    assertThat(new EmailVerificationMailRequest(value).rawEmail()).isNull();
    assertThat(new EmailVerificationCompletionRequest(value).rawToken()).isNull();
  }

  @ParameterizedTest
  @NullSource
  void 값이_없으면_null이다(Object value) {
    assertThat(new EmailVerificationMailRequest(value).rawEmail()).isNull();
    assertThat(new EmailVerificationCompletionRequest(value).rawToken()).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "  " + SAMPLE_COMPACT_VALUE + "  ",
        "\t" + SAMPLE_COMPACT_VALUE,
        SAMPLE_COMPACT_VALUE + "\n"
      })
  void token을_가공하지_않고_그대로_돌려준다(String rawToken) {
    // opaque 값이라 trim·정규화·대소문자 변환을 하지 않는다. 가공하면 검증 대상이 달라진다.
    assertThat(new EmailVerificationCompletionRequest(rawToken).rawToken()).isEqualTo(rawToken);
  }

  @Test
  void email도_DTO에서는_가공하지_않는다() {
    // trim·소문자 정규화는 application의 Email VO가 한 자리에서 한다.
    String raw = "  LEARNER@Example.COM  ";

    assertThat(new EmailVerificationMailRequest(raw).rawEmail()).isEqualTo(raw);
  }

  // ---------- 노출 ----------

  @Test
  void toString에_이메일도_토큰도_없다() {
    // DTO는 로그·예외 message로 흘러가기 쉽다. record 기본 toString은 모든 컴포넌트를 그대로 찍는다.
    assertThat(new EmailVerificationMailRequest(EMAIL))
        .hasToString("EmailVerificationMailRequest[REDACTED]");
    assertThat(new EmailVerificationCompletionRequest(SAMPLE_COMPACT_VALUE))
        .hasToString("EmailVerificationCompletionRequest[REDACTED]");

    assertThat(new EmailVerificationMailRequest(EMAIL).toString()).doesNotContain(EMAIL);
    assertThat(new EmailVerificationCompletionRequest(SAMPLE_COMPACT_VALUE).toString())
        .doesNotContain(SAMPLE_COMPACT_VALUE);
  }
}
