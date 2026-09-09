package com.itda.learning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UserSkillMemoryTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 12, 0);
  private static final String VERSION = "BKT_V1";

  private UserSkillMemory firstCorrect() {
    return UserSkillMemory.firstObservation(
        1L, 10L, true, new BigDecimal("0.640000"), VERSION, NOW, NOW);
  }

  @Test
  void 첫_관측으로_행을_만들면_카운터가_1이다() {
    UserSkillMemory memory = firstCorrect();

    assertThat(memory.getTotalSeen()).isEqualTo(1);
    assertThat(memory.getTotalCorrect()).isEqualTo(1);
    assertThat(memory.getTotalIncorrect()).isZero();
    assertThat(memory.hasConsistentCounters()).isTrue();
  }

  @Test
  void 확장_예약_컬럼은_MVP에서_NULL이다() {
    UserSkillMemory memory = firstCorrect();

    assertThat(memory.getEstimatedHalfLife()).isNull();
    assertThat(memory.getNextReviewAt()).isNull();
  }

  @Test
  void 관측_한_건마다_total_seen과_정오답_카운터_하나만_증가한다() {
    UserSkillMemory memory = firstCorrect();

    memory.applyObservation(false, new BigDecimal("0.300000"), VERSION, NOW, NOW);
    memory.applyObservation(true, new BigDecimal("0.500000"), VERSION, NOW, NOW);

    assertThat(memory.getTotalSeen()).isEqualTo(3);
    assertThat(memory.getTotalCorrect()).isEqualTo(2);
    assertThat(memory.getTotalIncorrect()).isEqualTo(1);
    assertThat(memory.hasConsistentCounters()).isTrue();
  }

  @Test
  void 마지막_연습_시각은_원본_Attempt_시각이고_갱신_시각은_분석_시각이다() {
    UserSkillMemory memory = firstCorrect();
    LocalDateTime attemptedAt = NOW.plusMinutes(5);
    LocalDateTime analyzedAt = NOW.plusMinutes(7);

    memory.applyObservation(true, new BigDecimal("0.874000"), VERSION, attemptedAt, analyzedAt);

    assertThat(memory.getLastPracticedAt()).isEqualTo(attemptedAt);
    assertThat(memory.getUpdatedAt()).isEqualTo(analyzedAt);
  }

  @ParameterizedTest
  @CsvSource({
    "0.000000, WEAK",
    "0.699999, WEAK",
    "0.700000, LEARNING",
    "0.874000, LEARNING",
    "0.949999, LEARNING",
    "0.950000, MASTERED",
    "1.000000, MASTERED"
  })
  void 숙련_구간은_확률의_0_70과_0_95_경계로_파생한다(String probability, MasteryBand expected) {
    UserSkillMemory memory =
        UserSkillMemory.firstObservation(
            1L, 10L, true, new BigDecimal(probability), VERSION, NOW, NOW);

    assertThat(memory.masteryBand()).isEqualTo(expected);
  }

  @Test
  void 두_번_연속_정답의_BKT_결과는_숙련_기준에_미달한다() {
    // ERD 5.4절: 같은 요소를 처음 두 번 연속 맞힌 값은 약 0.874로 0.95 미만이다.
    assertThat(MasteryBand.of(new BigDecimal("0.874"))).isEqualTo(MasteryBand.LEARNING);
  }

  @Test
  void 확률이_0과_1을_벗어나면_거부한다() {
    assertThatThrownBy(
            () ->
                UserSkillMemory.firstObservation(
                    1L, 10L, true, new BigDecimal("1.000001"), VERSION, NOW, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                UserSkillMemory.firstObservation(
                    1L, 10L, true, new BigDecimal("-0.000001"), VERSION, NOW, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
