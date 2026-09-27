package com.itda.learning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

/** 개인 정답률 집계가 LIVE 풀이를 세지 않는지 확인한다. LIVE 수업 풀이는 개인 정답률 통계에서도 제외한다(API 명세 8.1절, 사용자 결정). */
@DataJpaTest
class ProblemAttemptRepositoryTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 1, 0);

  @Autowired private ProblemAttemptRepository problemAttemptRepository;

  @Test
  void LIVE_풀이는_개인_정답률_집계에서_빠진다() {
    long userId = 1L;
    problemAttemptRepository.save(
        ProblemAttempt.forFreeSession(userId, 10L, 100L, 1, null, true, NOW));
    problemAttemptRepository.save(ProblemAttempt.forLiveLecture(userId, 20L, 200L, true, NOW));

    long total = problemAttemptRepository.countByUserIdAndAttemptMode(userId, AttemptMode.FREE);
    long correct =
        problemAttemptRepository.countByUserIdAndAttemptModeAndCorrectTrue(
            userId, AttemptMode.FREE);

    assertThat(total).isEqualTo(1);
    assertThat(correct).isEqualTo(1);
  }
}
