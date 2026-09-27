package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Argon2idPropertiesTest {

  @Test
  void 계약_최소_설정은_그대로_허용한다() {
    Argon2idProperties properties = new Argon2idProperties(19456, 2, 1);

    assertThat(properties.memoryKib()).isEqualTo(19456);
    assertThat(properties.iterations()).isEqualTo(2);
    assertThat(properties.parallelism()).isEqualTo(1);
  }

  @Test
  void 최소값보다_강한_설정도_허용한다() {
    assertThat(new Argon2idProperties(65536, 4, 2)).isNotNull();
    assertThat(new Argon2idProperties(262144, 10, 4)).isNotNull();
  }

  @ParameterizedTest
  @CsvSource({
    "16384, 2, 1", // Spring 기본값. 계약 최소 19456에 미달한다
    "19455, 2, 1",
    "0, 2, 1",
    "19456, 1, 1",
    "19456, 0, 1",
    "19456, 2, 0"
  })
  void 계약_최소값에_미달하는_설정은_기동_시점에_거부한다(int memoryKib, int iterations, int parallelism) {
    assertThatThrownBy(() -> new Argon2idProperties(memoryKib, iterations, parallelism))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({"262145, 2, 1", "9999999, 2, 1", "19456, 11, 1", "19456, 2, 5"})
  void 안전_상한을_넘는_설정도_거부한다(int memoryKib, int iterations, int parallelism) {
    assertThatThrownBy(() -> new Argon2idProperties(memoryKib, iterations, parallelism))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 거부_message에는_설정_키와_허용_범위만_들어간다() {
    assertThatThrownBy(() -> new Argon2idProperties(16384, 2, 1))
        .hasMessageContaining("itda.security.password.argon2.memory-kib")
        .hasMessageContaining("19456");
  }
}
