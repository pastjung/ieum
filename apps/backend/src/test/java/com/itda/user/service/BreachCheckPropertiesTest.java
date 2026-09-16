package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BreachCheckPropertiesTest {

  private static final String DEFAULT_BASE_URL = "https://api.pwnedpasswords.com/range/";

  @Test
  void 기본_설정을_그대로_허용한다() {
    BreachCheckProperties properties =
        new BreachCheckProperties(DEFAULT_BASE_URL, Duration.ofSeconds(2), Duration.ofSeconds(3));

    assertThat(properties.baseUrl()).isEqualTo(DEFAULT_BASE_URL);
    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
  }

  @Test
  void scheme_대소문자는_구분하지_않는다() {
    assertThat(
            new BreachCheckProperties(
                "HTTPS://api.pwnedpasswords.com/range/",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3)))
        .isNotNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://api.pwnedpasswords.com/range/", // http
        "ftp://example.test/range/", // ftp
        "range/", // 절대 URI가 아님
        "https://", // host 없음
        "https:///range/", // host 없음
        "https://user:secret@example.test/range/", // user-info 포함
        "https://user@example.test/range/", // user-info 포함
        "https://example.test/range/?mode=ntlm", // query 포함
        "https://example.test/range/#fragment", // fragment 포함
        "https://example.test/range", // 끝 슬래시 없음
        "https://example.test", // 경로 없음
        "https://exa mple.test/range/", // URI 문법 오류
        "https://example.test/ra nge/" // URI 문법 오류
      })
  void 안전하지_않은_base_url은_거부한다(String baseUrl) {
    assertThatThrownBy(
            () -> new BreachCheckProperties(baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(3)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "itda.security.password.breach.base-url은 사용자 정보·질의·프래그먼트가 없고 '/'로 끝나는 절대 https URL이어야 합니다.");
  }

  @Test
  void null_base_url도_같은_정적_message로_거부한다() {
    assertThatThrownBy(
            () -> new BreachCheckProperties(null, Duration.ofSeconds(2), Duration.ofSeconds(3)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("base-url");
  }

  @Test
  void 거부_message에는_입력한_URL이_들어가지_않는다() {
    assertThatThrownBy(
            () ->
                new BreachCheckProperties(
                    "https://user:secret@example.test/range/",
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(3)))
        .hasMessageNotContaining("secret")
        .hasMessageNotContaining("example.test");
  }

  @Test
  void 타임아웃이_0이거나_음수면_거부한다() {
    assertThatThrownBy(
            () -> new BreachCheckProperties(DEFAULT_BASE_URL, Duration.ZERO, Duration.ofSeconds(3)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new BreachCheckProperties(
                    DEFAULT_BASE_URL, Duration.ofSeconds(2), Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
