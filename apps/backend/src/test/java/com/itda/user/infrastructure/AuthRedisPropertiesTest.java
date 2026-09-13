package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuthRedisPropertiesTest {

  @Test
  void 허용한_환경_이름만_통과한다() {
    for (String prefix : AuthRedisProperties.ALLOWED_PREFIXES) {
      assertThatCode(() -> new AuthRedisProperties(prefix)).doesNotThrowAnyException();
    }
    assertThat(AuthRedisProperties.ALLOWED_PREFIXES)
        .containsExactlyInAnyOrder("local", "test", "prod");
  }

  @Test
  void 값이_없으면_기동에_실패한다() {
    assertThatThrownBy(() -> new AuthRedisProperties(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("key-prefix");
    assertThatThrownBy(() -> new AuthRedisProperties("  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 해석되지_않은_placeholder를_거부한다() {
    assertThatThrownBy(() -> new AuthRedisProperties("${AUTH_REDIS_KEY_PREFIX}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("placeholder");
  }

  @Test
  void 목록에_없는_환경_이름을_거부한다() {
    // 오타 하나가 다른 환경의 토큰 상태를 조용히 읽게 만든다.
    assertThatThrownBy(() -> new AuthRedisProperties("production"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuthRedisProperties("Test"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
