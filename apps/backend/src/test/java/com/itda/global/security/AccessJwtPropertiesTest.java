package com.itda.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.support.security.AccessTokenTestKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccessJwtPropertiesTest {

  private static final String KEY = AccessTokenTestKeys.randomKey();

  @Test
  void 정상_설정을_허용한다() {
    AccessJwtProperties properties =
        new AccessJwtProperties("itda-backend-test", "active-1", KEY, "");

    assertThat(properties.issuer()).isEqualTo("itda-backend-test");
    assertThat(properties.activeKid()).isEqualTo("active-1");
    assertThat(properties.retiredKeys()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"itda-backend-local", "itda-backend-test", "itda-backend-prod"})
  void 허용된_issuer만_받는다(String issuer) {
    assertThat(new AccessJwtProperties(issuer, "active-1", KEY, "")).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"itda-backend", "itda-backend-dev", "https://itda.example.test", "prod"})
  void 허용되지_않은_issuer는_거부한다(String issuer) {
    assertThatThrownBy(() -> new AccessJwtProperties(issuer, "active-1", KEY, ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("issuer");
  }

  @Test
  void 필수값이_없으면_거부한다() {
    assertThatThrownBy(() -> new AccessJwtProperties(null, "active-1", KEY, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessJwtProperties("itda-backend-test", " ", KEY, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessJwtProperties("itda-backend-test", "active-1", "", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 해석되지_않은_placeholder를_거부한다() {
    assertThatThrownBy(
            () ->
                new AccessJwtProperties(
                    "itda-backend-test", "active-1", "${AUTH_ACCESS_ACTIVE_KEY}", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("placeholder");
    assertThatThrownBy(() -> new AccessJwtProperties("${AUTH_JWT_ISSUER}", "active-1", KEY, ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("placeholder");
  }

  @ParameterizedTest
  @ValueSource(strings = {"kid with space", "kid,comma", "kid:colon", "kid#hash", "kid/slash"})
  void 안전하지_않은_kid_형식을_거부한다(String kid) {
    assertThatThrownBy(() -> new AccessJwtProperties("itda-backend-test", kid, KEY, ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kid");
  }

  @Test
  void kid_길이_상한을_넘으면_거부한다() {
    assertThatThrownBy(() -> new AccessJwtProperties("itda-backend-test", "a".repeat(65), KEY, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new AccessJwtProperties("itda-backend-test", "a".repeat(64), KEY, "")).isNotNull();
  }

  @Test
  void 예외_message에_key_값을_넣지_않는다() {
    assertThatThrownBy(() -> new AccessJwtProperties("itda-backend-dev", "active-1", KEY, ""))
        .hasMessageNotContaining(KEY);
  }

  @Test
  void toString은_설정_값을_가린다() {
    AccessJwtProperties properties =
        new AccessJwtProperties("itda-backend-test", "active-1", KEY, "retired-1:" + KEY);

    assertThat(properties.toString())
        .isEqualTo("AccessJwtProperties[REDACTED]")
        .doesNotContain(KEY);
  }
}
