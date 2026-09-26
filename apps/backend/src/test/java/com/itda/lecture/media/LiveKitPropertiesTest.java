package com.itda.lecture.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LiveKitPropertiesTest {

  @Test
  void 클라이언트_주소는_https를_wss로_바꾼다() {
    LiveKitProperties properties =
        new LiveKitProperties("https://openvidu.i-eum.site", "key", "secret");

    assertThat(properties.clientUrl()).isEqualTo("wss://openvidu.i-eum.site");
  }

  @Test
  void 미해결_플레이스홀더나_빈_값이면_기동을_막는다() {
    // Spring은 미해결 ${...}를 예외 없이 리터럴로 넘긴다 (application.yml local 프로필 주석 참고).
    assertThatThrownBy(() -> new LiveKitProperties("${LIVEKIT_URL}", "key", "secret"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LiveKitProperties("https://x", " ", "secret"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
