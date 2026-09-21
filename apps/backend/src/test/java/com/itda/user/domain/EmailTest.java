package com.itda.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailTest {

  @Test
  void 앞뒤_공백을_제거하고_소문자로_정규화한다() {
    assertThat(Email.of("  User@Example.COM ").getValue()).isEqualTo("user@example.com");
  }

  @Test
  void 대소문자만_다른_이메일은_같은_값이_된다() {
    assertThat(Email.of("a@b.com")).isEqualTo(Email.of("A@B.COM"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"no-at-sign", "a@b", "@b.com", "a@.com", " "})
  void 형식이_올바르지_않으면_거부한다(String raw) {
    assertThatThrownBy(() -> Email.of(raw)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 최대_길이를_넘으면_거부한다() {
    String tooLong = "a".repeat(Email.MAX_LENGTH) + "@example.com";

    assertThatThrownBy(() -> Email.of(tooLong)).isInstanceOf(IllegalArgumentException.class);
  }
}
