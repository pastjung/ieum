package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 메일 발송 설정의 불변식을 고정한다 (ERD 3.5절).
 *
 * <p>잘못된 값이 통과하면 드러나는 방식이 조용하고 위험하다. backoff가 0 이하면 {@code next_attempt_at}이 과거가 되어 다음 poll에서 곧바로
 * 다시 후보가 되고 시도 상한을 순식간에 태운다. 보존 기간이 음수면 정리 기준 시각이 미래가 되어 종결 행 전체가 즉시 삭제된다. 그래서 기동 단계에서 막는다.
 */
class MailDispatchPropertiesTest {

  private static final List<Duration> BACKOFF =
      List.of(
          Duration.ofSeconds(30),
          Duration.ofMinutes(1),
          Duration.ofMinutes(2),
          Duration.ofMinutes(4),
          Duration.ofMinutes(8));

  private static MailDispatchProperties properties(
      int maxAttempts,
      List<Duration> backoff,
      Duration maxBackoff,
      double jitterRatio,
      Duration providerTimeout,
      Duration visibilityTimeout,
      Duration terminalRetention) {
    return new MailDispatchProperties(
        maxAttempts,
        backoff,
        maxBackoff,
        jitterRatio,
        providerTimeout,
        visibilityTimeout,
        terminalRetention);
  }

  /** 계약 기본값 그대로. 각 테스트는 여기서 한 가지만 어긋나게 바꾼다. */
  private static MailDispatchProperties contractDefaults() {
    return properties(
        6,
        BACKOFF,
        Duration.ofMinutes(10),
        0.2,
        Duration.ofSeconds(60),
        Duration.ofMinutes(5),
        Duration.ofDays(7));
  }

  @Test
  void 계약_기본값은_그대로_통과한다() {
    MailDispatchProperties properties = contractDefaults();

    assertThat(properties.maxAttempts()).isEqualTo(6);
    assertThat(properties.backoff()).containsExactlyElementsOf(BACKOFF);
    assertThat(properties.maxBackoff()).isEqualTo(Duration.ofMinutes(10));
    assertThat(properties.jitterRatio()).isEqualTo(0.2);
    assertThat(properties.providerTimeout()).isEqualTo(Duration.ofSeconds(60));
    assertThat(properties.visibilityTimeout()).isEqualTo(Duration.ofMinutes(5));
    assertThat(properties.terminalRetention()).isEqualTo(Duration.ofDays(7));
  }

  // ---------- 시도 횟수 ----------

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void 시도_상한이_1_미만이면_거부한다(int maxAttempts) {
    assertThatThrownBy(
            () ->
                properties(
                    maxAttempts,
                    BACKOFF,
                    Duration.ofMinutes(10),
                    0.2,
                    Duration.ofSeconds(60),
                    Duration.ofMinutes(5),
                    Duration.ofDays(7)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 시도_상한_1은_경계로_허용한다() {
    assertThatCode(
            () ->
                properties(
                    1,
                    BACKOFF,
                    Duration.ofMinutes(10),
                    0.2,
                    Duration.ofSeconds(60),
                    Duration.ofMinutes(5),
                    Duration.ofDays(7)))
        .doesNotThrowAnyException();
  }

  // ---------- backoff ----------

  @Test
  void backoff가_없거나_비어_있으면_거부한다() {
    assertThatThrownBy(() -> withBackoff(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withBackoff(List.of())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void backoff에_null_원소가_있으면_거부한다() {
    assertThatThrownBy(() -> withBackoff(Arrays.asList(Duration.ofSeconds(30), null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void backoff에_0이나_음수가_있으면_거부한다() {
    // 0 이하면 next_attempt_at이 현재 이하가 되어 즉시 재시도 loop가 된다.
    assertThatThrownBy(() -> withBackoff(List.of(Duration.ofSeconds(30), Duration.ZERO)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withBackoff(List.of(Duration.ofSeconds(-30))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void backoff는_생성_뒤_바뀌지_않는다() {
    List<Duration> mutable = new ArrayList<>(BACKOFF);
    MailDispatchProperties properties = withBackoff(mutable);

    mutable.clear();
    mutable.add(Duration.ofDays(1));

    assertThat(properties.backoff()).containsExactlyElementsOf(BACKOFF);
    assertThat(properties.backoffFor(1)).isEqualTo(Duration.ofSeconds(30));
  }

  // ---------- Duration 양수 ----------

  @Test
  void backoff_상한이_없거나_0_이하면_거부한다() {
    assertThatThrownBy(() -> withMaxBackoff(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withMaxBackoff(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withMaxBackoff(Duration.ofMinutes(-10)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 제공자_제한_시간이_없거나_0_이하면_거부한다() {
    assertThatThrownBy(() -> withProviderTimeout(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withProviderTimeout(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withProviderTimeout(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 가시성_만료가_없거나_0_이하면_거부한다() {
    assertThatThrownBy(() -> withVisibilityTimeout(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withVisibilityTimeout(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withVisibilityTimeout(Duration.ofMinutes(-5)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 보존_기간이_없거나_0_이하면_거부한다() {
    // 음수면 정리 기준 시각이 미래가 되어 종결 행 전체가 즉시 삭제된다.
    assertThatThrownBy(() -> withTerminalRetention(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withTerminalRetention(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withTerminalRetention(Duration.ofDays(-7)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 나노초_단위도_양수라서_통과한다() {
    assertThatCode(() -> withTerminalRetention(Duration.ofNanos(1))).doesNotThrowAnyException();
  }

  // ---------- 지터 ----------

  @ParameterizedTest
  @ValueSource(doubles = {-0.0001, -1, 1, 1.5, Double.MAX_VALUE})
  void 지터_비율이_범위를_벗어나면_거부한다(double jitterRatio) {
    assertThatThrownBy(() -> withJitter(jitterRatio)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 지터_비율의_NaN과_무한대를_거부한다() {
    assertThatThrownBy(() -> withJitter(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withJitter(Double.POSITIVE_INFINITY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withJitter(Double.NEGATIVE_INFINITY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 지터_비율의_0과_1_직전은_허용한다() {
    assertThatCode(() -> withJitter(0)).doesNotThrowAnyException();
    assertThatCode(() -> withJitter(Math.nextDown(1.0))).doesNotThrowAnyException();
  }

  // ---------- 제한 시간 관계 ----------

  @Test
  void 제공자_제한_시간이_가시성_만료_이상이면_거부한다() {
    assertThatThrownBy(
            () ->
                properties(
                    6,
                    BACKOFF,
                    Duration.ofMinutes(10),
                    0.2,
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(5),
                    Duration.ofDays(7)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                properties(
                    6,
                    BACKOFF,
                    Duration.ofMinutes(10),
                    0.2,
                    Duration.ofMinutes(6),
                    Duration.ofMinutes(5),
                    Duration.ofDays(7)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- 예외 message ----------

  @Test
  void 거부_message에_설정값을_담지_않는다() {
    assertThatThrownBy(() -> withTerminalRetention(Duration.ofDays(-7)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("-7")
        .hasMessageNotContaining("PT")
        .hasMessageNotContaining("itda.mail.dispatch");
  }

  // ---------- 도우미 ----------

  private static MailDispatchProperties withBackoff(List<Duration> backoff) {
    return properties(
        6,
        backoff,
        Duration.ofMinutes(10),
        0.2,
        Duration.ofSeconds(60),
        Duration.ofMinutes(5),
        Duration.ofDays(7));
  }

  private static MailDispatchProperties withMaxBackoff(Duration maxBackoff) {
    return properties(
        6,
        BACKOFF,
        maxBackoff,
        0.2,
        Duration.ofSeconds(60),
        Duration.ofMinutes(5),
        Duration.ofDays(7));
  }

  private static MailDispatchProperties withJitter(double jitterRatio) {
    return properties(
        6,
        BACKOFF,
        Duration.ofMinutes(10),
        jitterRatio,
        Duration.ofSeconds(60),
        Duration.ofMinutes(5),
        Duration.ofDays(7));
  }

  private static MailDispatchProperties withProviderTimeout(Duration providerTimeout) {
    return properties(
        6,
        BACKOFF,
        Duration.ofMinutes(10),
        0.2,
        providerTimeout,
        Duration.ofMinutes(5),
        Duration.ofDays(7));
  }

  private static MailDispatchProperties withVisibilityTimeout(Duration visibilityTimeout) {
    return properties(
        6,
        BACKOFF,
        Duration.ofMinutes(10),
        0.2,
        Duration.ofSeconds(60),
        visibilityTimeout,
        Duration.ofDays(7));
  }

  private static MailDispatchProperties withTerminalRetention(Duration terminalRetention) {
    return properties(
        6,
        BACKOFF,
        Duration.ofMinutes(10),
        0.2,
        Duration.ofSeconds(60),
        Duration.ofMinutes(5),
        terminalRetention);
  }
}
