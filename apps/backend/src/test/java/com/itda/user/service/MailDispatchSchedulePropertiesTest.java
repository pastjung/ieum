package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 스케줄 설정의 기본값과 허용 범위를 고정한다. README의 표와 같아야 한다. */
class MailDispatchSchedulePropertiesTest {

  @Test
  void 기본값은_보수적인_유한_값이다() {
    MailDispatchScheduleProperties properties =
        new MailDispatchScheduleProperties(
            Duration.ofSeconds(10), Duration.ofMinutes(5), 20, Duration.ofSeconds(30));

    assertThat(properties.pollInterval()).isEqualTo(Duration.ofSeconds(10));
    assertThat(properties.cleanupInterval()).isEqualTo(Duration.ofMinutes(5));
    assertThat(properties.batchSize()).isEqualTo(20);
    assertThat(properties.shutdownWait()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void poll_주기는_1초_이상_10분_이하다() {
    assertThatThrownBy(() -> withPoll(Duration.ofMillis(999)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.dispatch.schedule.poll-interval");
    assertThatThrownBy(() -> withPoll(Duration.ofMinutes(10).plusMillis(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.dispatch.schedule.poll-interval");
    assertThatThrownBy(() -> withPoll(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.dispatch.schedule.poll-interval");
    assertThat(withPoll(Duration.ofSeconds(1)).pollInterval()).isEqualTo(Duration.ofSeconds(1));
    assertThat(withPoll(Duration.ofMinutes(10)).pollInterval()).isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  void 정리_주기는_1분_이상_24시간_이하다() {
    assertThatThrownBy(() -> withCleanup(Duration.ofSeconds(59)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.dispatch.schedule.cleanup-interval");
    assertThatThrownBy(() -> withCleanup(Duration.ofHours(24).plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.dispatch.schedule.cleanup-interval");
    assertThat(withCleanup(Duration.ofMinutes(1)).cleanupInterval())
        .isEqualTo(Duration.ofMinutes(1));
    assertThat(withCleanup(Duration.ofHours(24)).cleanupInterval()).isEqualTo(Duration.ofHours(24));
  }

  @Test
  void batch_크기는_1_이상_500_이하다() {
    assertThatThrownBy(() -> withBatch(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.dispatch.schedule.batch-size");
    assertThatThrownBy(() -> withBatch(501))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.dispatch.schedule.batch-size");
    assertThat(withBatch(1).batchSize()).isEqualTo(1);
    assertThat(withBatch(500).batchSize()).isEqualTo(500);
  }

  @Test
  void 종료_대기는_1초_이상_5분_이하다() {
    assertThatThrownBy(() -> withShutdown(Duration.ofMillis(500)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.dispatch.schedule.shutdown-wait");
    assertThatThrownBy(() -> withShutdown(Duration.ofMinutes(5).plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.dispatch.schedule.shutdown-wait");
    assertThat(withShutdown(Duration.ofSeconds(1)).shutdownWait()).isEqualTo(Duration.ofSeconds(1));
    assertThat(withShutdown(Duration.ofMinutes(5)).shutdownWait()).isEqualTo(Duration.ofMinutes(5));
  }

  private static MailDispatchScheduleProperties withPoll(Duration poll) {
    return new MailDispatchScheduleProperties(
        poll, Duration.ofMinutes(5), 20, Duration.ofSeconds(30));
  }

  private static MailDispatchScheduleProperties withCleanup(Duration cleanup) {
    return new MailDispatchScheduleProperties(
        Duration.ofSeconds(10), cleanup, 20, Duration.ofSeconds(30));
  }

  private static MailDispatchScheduleProperties withBatch(int batch) {
    return new MailDispatchScheduleProperties(
        Duration.ofSeconds(10), Duration.ofMinutes(5), batch, Duration.ofSeconds(30));
  }

  private static MailDispatchScheduleProperties withShutdown(Duration wait) {
    return new MailDispatchScheduleProperties(
        Duration.ofSeconds(10), Duration.ofMinutes(5), 20, wait);
  }
}
