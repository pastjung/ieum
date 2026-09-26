package com.itda;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/** 운영(main)과 테스트가 같은 시간대(UTC)에서 돈다 — 둘이 다르면 저장 시각이 환경마다 9시간씩 어긋난다 */
class TimeZoneTest {

  @Test
  void 테스트_JVM은_UTC로_돈다() {
    assertThat(TimeZone.getDefault().toZoneId().normalized()).isEqualTo(ZoneOffset.UTC);
  }

  @Test
  void main의_시간대_고정은_UTC다() {
    TimeZone before = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
      ItdaApplication.useUtcAsDefaultTimeZone();
      assertThat(TimeZone.getDefault().toZoneId().normalized()).isEqualTo(ZoneOffset.UTC);
    } finally {
      TimeZone.setDefault(before);
    }
  }
}
