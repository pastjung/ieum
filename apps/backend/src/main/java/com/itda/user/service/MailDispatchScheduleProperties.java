package com.itda.user.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 메일 발송 작업 스케줄 설정.
 *
 * <p>{@link MailDispatchProperties}에 합치지 않고 따로 둔 이유: 그 record는 ERD 3.5절의 계약 값(재시도·backoff·제한
 * 시간·보존)을 담고 {@link MailDispatchPolicy}로 worker에 그대로 들어간다. 여기의 값은 계약이 "구현에서 정한다"고 남긴 실행 주기·batch·종료
 * 대기이고 worker가 아니라 스케줄러가 쓴다. 합치면 계약 값 record의 생성자와 기존 테스트가 함께 바뀌고, 정책 값과 실행 값의 경계가 흐려진다.
 *
 * <p>활성화 스위치 {@code itda.mail.dispatch.schedule.enabled}는 이 record에 없다. 그 값은 {@link
 * MailDispatchConfiguration}이 조립 자체를 할지 말지 결정하는 조건이며, 이 record는 켜졌을 때만 바인딩된다.
 *
 * <p>모든 값에 유한한 허용 범위를 둔다. 범위 밖 값이 통과하면 드러나는 방식이 조용하다 — poll 주기가 0이면 DB를 쉬지 않고 두드리고, batch가 지나치게 크면
 * 한 번의 poll이 가시성 만료 안에 끝나지 못해 다른 worker가 같은 작업을 다시 선점한다.
 */
@ConfigurationProperties("itda.mail.dispatch.schedule")
public record MailDispatchScheduleProperties(
    @DefaultValue("10s") Duration pollInterval,
    @DefaultValue("5m") Duration cleanupInterval,
    @DefaultValue("20") int batchSize,
    @DefaultValue("30s") Duration shutdownWait) {

  private static final String PREFIX = "itda.mail.dispatch.schedule.";

  public MailDispatchScheduleProperties {
    requireRange(pollInterval, "poll-interval", Duration.ofSeconds(1), Duration.ofMinutes(10));
    requireRange(cleanupInterval, "cleanup-interval", Duration.ofMinutes(1), Duration.ofHours(24));
    if (batchSize < 1 || batchSize > 500) {
      throw new IllegalArgumentException(PREFIX + "batch-size는 1 이상 500 이하여야 합니다.");
    }
    requireRange(shutdownWait, "shutdown-wait", Duration.ofSeconds(1), Duration.ofMinutes(5));
  }

  private static void requireRange(Duration value, String name, Duration min, Duration max) {
    if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
      throw new IllegalArgumentException(
          PREFIX + name + "은(는) " + min + " 이상 " + max + " 이하여야 합니다.");
    }
  }
}
