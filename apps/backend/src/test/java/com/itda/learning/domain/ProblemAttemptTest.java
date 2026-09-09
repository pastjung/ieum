package com.itda.learning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 채점은 서버 계산이므로 점 단위 정확도의 경계값을 우선 검증한다 (ERD 5.2절). */
class ProblemAttemptTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 12, 0);
  private static final String KEY = "0b3f8a1c-1111-4222-8333-444455556666";

  private ProblemAttempt freeAttempt(List<CellSubmission> submissions) {
    return ProblemAttempt.forFreeSession(1L, 2L, 3L, 1, 4L, submissions, 1500, KEY, NOW);
  }

  @Test
  void 모든_Cell이_일치하면_정답이고_점수는_1이다() {
    ProblemAttempt attempt =
        freeAttempt(List.of(CellSubmission.of(0b000111, 0b000111), CellSubmission.of(0, 0)));

    assertThat(attempt.isCorrect()).isTrue();
    assertThat(attempt.getScore()).isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void 한_점만_틀리면_오답이고_점수는_점_단위_정확도다() {
    // 1 Cell = 점 위치 6개. 오류 점 1개 → (6-1)/6 = 0.8333
    ProblemAttempt attempt = freeAttempt(List.of(CellSubmission.of(0b111111, 0b111110)));

    assertThat(attempt.isCorrect()).isFalse();
    assertThat(attempt.getScore()).isEqualByComparingTo(new BigDecimal("0.8333"));
  }

  @Test
  void 두_Cell의_분모는_점_위치_12개다() {
    // 오류 점 3개 → (12-3)/12 = 0.7500
    ProblemAttempt attempt =
        freeAttempt(
            List.of(CellSubmission.of(0b000111, 0b000000), CellSubmission.of(0b111000, 0b111000)));

    assertThat(attempt.getScore()).isEqualByComparingTo(new BigDecimal("0.7500"));
  }

  @Test
  void 모든_점이_틀리면_점수는_0이다() {
    ProblemAttempt attempt = freeAttempt(List.of(CellSubmission.of(0b111111, 0b000000)));

    assertThat(attempt.isCorrect()).isFalse();
    assertThat(attempt.getScore()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void 빈_점형_입력은_누락이_아니라_명시적_응답으로_채점한다() {
    ProblemAttempt attempt = freeAttempt(List.of(CellSubmission.of(0, 0)));

    assertThat(attempt.isCorrect()).isTrue();
    assertThat(attempt.getCells().get(0).getInputPattern().isEmpty()).isTrue();
  }

  @Test
  void 차이_마스크는_서버가_XOR로_계산한다() {
    ProblemAttempt attempt = freeAttempt(List.of(CellSubmission.of(0b000111, 0b000101)));

    AttemptCell cell = attempt.getCells().get(0);
    assertThat(cell.getDiffMask().getValue()).isEqualTo(0b000010);
    assertThat(cell.errorDotCount()).isEqualTo(1);
    assertThat(cell.isCorrect()).isFalse();
  }

  @Test
  void Cell_순번은_제출_순서대로_1부터_붙는다() {
    ProblemAttempt attempt =
        freeAttempt(
            List.of(CellSubmission.of(1, 1), CellSubmission.of(2, 2), CellSubmission.of(4, 4)));

    assertThat(attempt.getCells()).extracting(AttemptCell::getCellIndex).containsExactly(1, 2, 3);
  }

  @Test
  void Cell의_접수_시각은_Attempt와_같다() {
    ProblemAttempt attempt = freeAttempt(List.of(CellSubmission.of(1, 1)));

    assertThat(attempt.getCells().get(0).getRespondedAt()).isEqualTo(attempt.getAttemptedAt());
  }

  @Test
  void FREE_풀이는_세션_연결이_있고_수업이_없다() {
    ProblemAttempt attempt = freeAttempt(List.of(CellSubmission.of(1, 1)));

    assertThat(attempt.getAttemptMode()).isEqualTo(AttemptMode.FREE);
    assertThat(attempt.getLearningSessionId()).isEqualTo(3L);
    assertThat(attempt.getSessionItemNo()).isEqualTo(1);
    assertThat(attempt.getCurriculumStageId()).isEqualTo(4L);
    assertThat(attempt.getLectureId()).isNull();
  }

  @Test
  void LIVE_풀이는_수업이_있고_세션_연결과_기준_단계가_없다() {
    ProblemAttempt attempt =
        ProblemAttempt.forLiveLecture(1L, 2L, 9L, List.of(CellSubmission.of(1, 1)), null, KEY, NOW);

    assertThat(attempt.getAttemptMode()).isEqualTo(AttemptMode.LIVE);
    assertThat(attempt.getLectureId()).isEqualTo(9L);
    assertThat(attempt.getLearningSessionId()).isNull();
    assertThat(attempt.getSessionItemNo()).isNull();
    assertThat(attempt.getCurriculumStageId()).isNull();
  }

  @Test
  void FREE_풀이에_세션_연결이_빠지면_거부한다() {
    assertThatThrownBy(
            () ->
                ProblemAttempt.forFreeSession(
                    1L, 2L, null, null, 4L, List.of(CellSubmission.of(1, 1)), null, KEY, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void LIVE_풀이에_수업이_빠지면_거부한다() {
    assertThatThrownBy(
            () ->
                ProblemAttempt.forLiveLecture(
                    1L, 2L, null, List.of(CellSubmission.of(1, 1)), null, KEY, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 풀이_시간은_측정_불가면_NULL이고_음수는_거부한다() {
    ProblemAttempt attempt =
        ProblemAttempt.forFreeSession(
            1L, 2L, 3L, 1, 4L, List.of(CellSubmission.of(1, 1)), null, KEY, NOW);
    assertThat(attempt.getDurationMs()).isNull();

    assertThatThrownBy(
            () ->
                ProblemAttempt.forFreeSession(
                    1L, 2L, 3L, 1, 4L, List.of(CellSubmission.of(1, 1)), -1, KEY, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 정답_요소의_오류_수가_0이_아니면_거부한다() {
    ProblemAttempt attempt = freeAttempt(List.of(CellSubmission.of(1, 1)));

    assertThatThrownBy(() -> attempt.addSkillResult(10L, true, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 오답_요소의_오류_수가_0이면_거부한다() {
    ProblemAttempt attempt = freeAttempt(List.of(CellSubmission.of(1, 1)));

    assertThatThrownBy(() -> attempt.addSkillResult(10L, false, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 같은_요소의_평가는_두_번_붙일_수_없다() {
    ProblemAttempt attempt = freeAttempt(List.of(CellSubmission.of(1, 1)));
    attempt.addSkillResult(10L, true, 0);

    assertThatThrownBy(() -> attempt.addSkillResult(10L, false, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 요소_평가_시각은_Attempt_접수_시각과_같다() {
    ProblemAttempt attempt = freeAttempt(List.of(CellSubmission.of(1, 1)));

    attempt.addSkillResult(10L, true, 0);

    assertThat(attempt.getSkillResults().get(0).getEvaluatedAt())
        .isEqualTo(attempt.getAttemptedAt());
  }

  @Test
  void 요소_평가_집합이_문제의_연결_요소와_일치하는지_확인한다() {
    ProblemAttempt attempt = freeAttempt(List.of(CellSubmission.of(1, 1)));
    attempt.addSkillResult(10L, true, 0);
    attempt.addSkillResult(11L, false, 2);

    assertThat(attempt.coversExactly(Set.of(10L, 11L))).isTrue();
    assertThat(attempt.coversExactly(Set.of(10L))).isFalse();
    assertThat(attempt.coversExactly(Set.of(10L, 11L, 12L))).isFalse();
  }

  @Test
  void Cell_제출이_없으면_거부한다() {
    assertThatThrownBy(() -> freeAttempt(List.of())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void Cell_제출_목록에_빈_항목이_있으면_몇_번째인지_알리며_거부한다() {
    List<CellSubmission> withNull = Arrays.asList(CellSubmission.of(1, 1), null);

    assertThatThrownBy(() -> freeAttempt(withNull))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("2번째");
  }

  @Test
  void 멱등_키는_필수다() {
    assertThatThrownBy(
            () ->
                ProblemAttempt.forFreeSession(
                    1L, 2L, 3L, 1, 4L, List.of(CellSubmission.of(1, 1)), null, "  ", NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
