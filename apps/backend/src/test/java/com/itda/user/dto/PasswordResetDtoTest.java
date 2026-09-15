package com.itda.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.user.service.PasswordResetService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 비밀번호 재설정 두 요청 DTO의 값 판정과 노출 범위를 고정한다 (API 명세 3.4.2절).
 *
 * <p>JSON 문자열만 값으로 인정하고 나머지는 전부 {@code null}로 접는다. 문자열은 가공하지 않는다 — token의 trim 금지와 비밀번호의 공백 보존이 여기서
 * 무너지면 Service의 판정 순서가 의미를 잃는다.
 */
class PasswordResetDtoTest {

  private static final String EMAIL = "learner@example.com";
  private static final String SAMPLE_COMPACT_VALUE = "header.payload.signature";
  private static final String NEW_PASSWORD = "충분히 긴 새 비밀번호 문구";

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
  void 문자열이면_그대로_넘긴다() {
    PasswordResetService.Command command =
        new PasswordResetCompletionRequest(SAMPLE_COMPACT_VALUE, NEW_PASSWORD).toCommand();

    assertThat(new PasswordResetMailRequest(EMAIL).rawEmail()).isEqualTo(EMAIL);
    assertThat(command.token()).isEqualTo(SAMPLE_COMPACT_VALUE);
    assertThat(command.newPassword()).isEqualTo(NEW_PASSWORD);
  }

  @ParameterizedTest
  @MethodSource("nonStringValues")
  void 문자열이_아니면_null로_접는다(Object value) {
    PasswordResetService.Command command =
        new PasswordResetCompletionRequest(value, value).toCommand();

    // 누락·null·비문자열이 모두 같은 422라 여기서 구분하지 않는다.
    assertThat(new PasswordResetMailRequest(value).rawEmail()).isNull();
    assertThat(command.token()).isNull();
    assertThat(command.newPassword()).isNull();
  }

  @ParameterizedTest
  @NullSource
  void 값이_없으면_null이다(Object value) {
    PasswordResetService.Command command =
        new PasswordResetCompletionRequest(value, value).toCommand();

    assertThat(new PasswordResetMailRequest(value).rawEmail()).isNull();
    assertThat(command.token()).isNull();
    assertThat(command.newPassword()).isNull();
  }

  @Test
  void 한_필드만_문자열이_아니면_그_필드만_null이다() {
    assertThat(new PasswordResetCompletionRequest(123, NEW_PASSWORD).toCommand().token()).isNull();
    assertThat(
            new PasswordResetCompletionRequest(SAMPLE_COMPACT_VALUE, 123).toCommand().newPassword())
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "  " + SAMPLE_COMPACT_VALUE + "  ",
        "\t" + SAMPLE_COMPACT_VALUE,
        SAMPLE_COMPACT_VALUE + "\n"
      })
  void token을_가공하지_않고_그대로_넘긴다(String rawToken) {
    // opaque 값이라 trim·정규화·대소문자 변환을 하지 않는다. 가공하면 검증 대상이 달라진다.
    assertThat(new PasswordResetCompletionRequest(rawToken, NEW_PASSWORD).toCommand().token())
        .isEqualTo(rawToken);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "  " + NEW_PASSWORD + "  "})
  void 새_비밀번호의_빈_값과_공백을_그대로_넘긴다(String rawNewPassword) {
    // 빈 값·공백 값은 필드 누락이 아니다. 비밀번호 정책이 판정할 값이므로 null로 접지 않는다.
    assertThat(
            new PasswordResetCompletionRequest(SAMPLE_COMPACT_VALUE, rawNewPassword)
                .toCommand()
                .newPassword())
        .isEqualTo(rawNewPassword);
  }

  @Test
  void email도_DTO에서는_가공하지_않는다() {
    String raw = "  LEARNER@Example.COM  ";

    assertThat(new PasswordResetMailRequest(raw).rawEmail()).isEqualTo(raw);
  }

  // ---------- 노출 ----------

  @Test
  void toString에_이메일도_토큰도_비밀번호도_없다() {
    PasswordResetCompletionRequest completion =
        new PasswordResetCompletionRequest(SAMPLE_COMPACT_VALUE, NEW_PASSWORD);

    assertThat(new PasswordResetMailRequest(EMAIL))
        .hasToString("PasswordResetMailRequest[REDACTED]");
    assertThat(completion).hasToString("PasswordResetCompletionRequest[REDACTED]");
    assertThat(completion.toCommand()).hasToString("PasswordResetCommand[REDACTED]");

    assertThat(completion.toString())
        .doesNotContain(SAMPLE_COMPACT_VALUE)
        .doesNotContain(NEW_PASSWORD);
    assertThat(completion.toCommand().toString())
        .doesNotContain(SAMPLE_COMPACT_VALUE)
        .doesNotContain(NEW_PASSWORD);
  }
}
