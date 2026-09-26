package com.itda.user.service;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 메일 발송 작업 처리 설정 (ERD 3.5절).
 *
 * <p>계약이 "설정값은 논리 이름과 기본값으로 고정하고 실제 환경변수 키는 구현에서 정한다"고 했으므로 기본값은 계약 값 그대로 두고 환경변수로 열지 않는다. 지금 열면
 * 운영에서 계약과 다른 값이 들어가도 드러나지 않는다.
 *
 * <p>poll 주기·batch 크기·scheduler 활성화는 여기에 없다. 실제 scheduler는 제공자 adapter가 생기는 후속 이슈에서 켠다. 이번 범위의
 * worker는 호출자가 batch 크기를 넘겨 한 번 실행한다.
 */
@ConfigurationProperties("itda.mail.dispatch")
public record MailDispatchProperties(
    @DefaultValue("6") int maxAttempts,
    @DefaultValue({"30s", "1m", "2m", "4m", "8m"}) List<Duration> backoff,
    @DefaultValue("10m") Duration maxBackoff,
    @DefaultValue("0.2") double jitterRatio,
    @DefaultValue("60s") Duration providerTimeout,
    @DefaultValue("5m") Duration visibilityTimeout,
    @DefaultValue("7d") Duration terminalRetention)
    implements MailDispatchPolicy {

  public MailDispatchProperties {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("최대 처리 시도 횟수는 1 이상이어야 합니다.");
    }
    if (backoff == null || backoff.isEmpty()) {
      throw new IllegalArgumentException("backoff 간격이 없습니다.");
    }
    // 원소 검사를 복사보다 먼저 한다. List.copyOf는 null 원소에서 NullPointerException을 던지므로
    // 순서를 바꾸면 "어떤 설정이 잘못됐는지" 알려 주지 못하는 예외가 먼저 나간다.
    for (Duration interval : backoff) {
      requirePositive(interval, "backoff 간격");
    }
    // 방어적 복사. 바인딩 뒤에 목록이 바뀌면 worker가 실행 중에 다른 대기 시간을 쓰게 된다.
    backoff = List.copyOf(backoff);
    requirePositive(maxBackoff, "backoff 상한");
    requirePositive(providerTimeout, "제공자 호출 제한 시간");
    requirePositive(visibilityTimeout, "가시성 만료");
    requirePositive(terminalRetention, "종결 행 보존 기간");
    if (!Double.isFinite(jitterRatio) || jitterRatio < 0 || jitterRatio >= 1) {
      throw new IllegalArgumentException("지터 비율은 0 이상 1 미만의 유한한 값이어야 합니다.");
    }
    // 계약: 모든 외부 호출 제한 시간의 합에 안전 여유를 더한 값이 가시성 만료보다 작아야 한다.
    if (providerTimeout.compareTo(visibilityTimeout) >= 0) {
      throw new IllegalArgumentException("제공자 제한 시간이 가시성 만료보다 짧아야 합니다.");
    }
  }

  /**
   * 0 이하이거나 없는 값을 기동 단계에서 막는다.
   *
   * <p>음수·0이 통과하면 드러나는 방식이 조용하고 위험하다. backoff가 0 이하면 {@code next_attempt_at}이 과거가 되어 다음 poll에서 곧바로
   * 다시 후보가 되고 시도 상한을 순식간에 태운다. 보존 기간이 음수면 정리 기준 시각이 미래가 되어 <b>종결 행 전체가 즉시 삭제</b>된다.
   *
   * <p>message에 값이나 환경변수 원문을 넣지 않는다. 어떤 항목인지만 알려 준다.
   */
  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + "은(는) 0보다 커야 합니다.");
    }
  }

  /**
   * {@code attemptCount}번째 시도가 실패했을 때의 다음 대기 시간. 목록을 넘어가면 내부 상한을 쓴다.
   *
   * <p>지터는 호출자가 적용한다 — 시각 계산에 난수를 섞는 지점을 한 곳으로 모아 테스트가 결정적으로 남게 한다.
   */
  public Duration backoffFor(int attemptCount) {
    int index = Math.max(attemptCount, 1) - 1;
    Duration base = index < this.backoff.size() ? this.backoff.get(index) : this.maxBackoff;
    return base.compareTo(this.maxBackoff) > 0 ? this.maxBackoff : base;
  }
}
