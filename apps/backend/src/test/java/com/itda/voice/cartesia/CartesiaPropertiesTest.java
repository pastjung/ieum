package com.itda.voice.cartesia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CartesiaPropertiesTest {

  private static final String BASE_URL = "https://api.cartesia.ai";
  private static final String VERSION = "2026-08-14";
  private static final Duration CONNECT = Duration.ofSeconds(5);
  private static final Duration CLONE = Duration.ofSeconds(60);
  private static final Duration SPEECH = Duration.ofSeconds(20);

  @Test
  void API_키가_없어도_기동을_막지_않고_빈_문자열로_둔다() {
    CartesiaProperties properties =
        new CartesiaProperties(BASE_URL, null, VERSION, CONNECT, CLONE, SPEECH);

    assertThat(properties.apiKey()).isEmpty();
  }

  @Test
  void API_키의_앞뒤_공백은_지운다() {
    CartesiaProperties properties =
        new CartesiaProperties(BASE_URL, "  key-1  ", VERSION, CONNECT, CLONE, SPEECH);

    assertThat(properties.apiKey()).isEqualTo("key-1");
  }

  @Test
  void 주소가_없으면_기동_단계에서_실패한다() {
    assertThatThrownBy(() -> new CartesiaProperties(" ", "key", VERSION, CONNECT, CLONE, SPEECH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.cartesia.base-url");
  }

  @Test
  void 버전_헤더_값이_없으면_기동_단계에서_실패한다() {
    assertThatThrownBy(() -> new CartesiaProperties(BASE_URL, "key", null, CONNECT, CLONE, SPEECH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.cartesia.api-version");
  }

  @Test
  void 제한_시간이_0이하면_기동_단계에서_실패한다() {
    assertThatThrownBy(
            () -> new CartesiaProperties(BASE_URL, "key", VERSION, CONNECT, Duration.ZERO, SPEECH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.cartesia.clone-timeout");
  }
}
