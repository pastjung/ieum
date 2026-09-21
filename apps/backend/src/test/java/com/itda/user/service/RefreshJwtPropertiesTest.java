package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RefreshJwtPropertiesTest {

  @Test
  void 값이_모두_채워지면_통과한다() {
    assertThatCode(() -> RefreshTokenTestKeys.properties("refresh-active"))
        .doesNotThrowAnyException();
  }

  @Test
  void active_kid가_비면_기동에_실패한다() {
    assertThatThrownBy(() -> new RefreshJwtProperties("  ", RefreshTokenTestKeys.randomKey(), ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("active-kid");
  }

  @Test
  void active_key가_없으면_기동에_실패한다() {
    assertThatThrownBy(() -> new RefreshJwtProperties("refresh-active", null, ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("active-key");
  }

  @Test
  void 해석되지_않은_placeholder를_거부한다() {
    assertThatThrownBy(
            () -> new RefreshJwtProperties("refresh-active", "${AUTH_REFRESH_ACTIVE_KEY}", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("placeholder");
    assertThatThrownBy(
            () ->
                new RefreshJwtProperties(
                    "refresh-active",
                    RefreshTokenTestKeys.randomKey(),
                    "${AUTH_REFRESH_RETIRED_KEYS}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("placeholder");
  }

  @Test
  void 구분자가_섞인_kid를_거부한다() {
    assertThatThrownBy(() -> new RefreshJwtProperties("a:b", RefreshTokenTestKeys.randomKey(), ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RefreshJwtProperties("a,b", RefreshTokenTestKeys.randomKey(), ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void retired_keys가_null이면_기동에_실패한다() {
    assertThatThrownBy(
            () ->
                new RefreshJwtProperties("refresh-active", RefreshTokenTestKeys.randomKey(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retired-keys");
  }

  @Test
  void 실패_message에_key_원문이_없다() {
    String key = RefreshTokenTestKeys.randomKey();

    assertThatThrownBy(() -> new RefreshJwtProperties("a:b", key, "")).hasMessageNotContaining(key);
  }

  @Test
  void toString은_설정값을_가린다() {
    String key = RefreshTokenTestKeys.randomKey();

    RefreshJwtProperties properties = new RefreshJwtProperties("refresh-active", key, "");

    assertThat(properties.toString()).isEqualTo("RefreshJwtProperties[REDACTED]");
    assertThat(properties.toString()).doesNotContain(key);
  }
}
