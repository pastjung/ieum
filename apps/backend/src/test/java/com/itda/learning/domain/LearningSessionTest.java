package com.itda.learning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LearningSessionTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 12, 0);

  /** ERD 4.7절대로 1~5에 서로 다른 문제 5개, 6~10에 그 5개를 한 번씩 배정한다. */
  private static List<AssignedProblem> validAssignment() {
    List<AssignedProblem> items = new ArrayList<>();
    long[] firstHalf = {101, 102, 103, 104, 105};
    long[] secondHalf = {103, 101, 105, 102, 104};
    for (int i = 0; i < firstHalf.length; i++) {
      items.add(AssignedProblem.of(i + 1, firstHalf[i]));
    }
    for (int i = 0; i < secondHalf.length; i++) {
      items.add(AssignedProblem.of(i + 6, secondHalf[i]));
    }
    return items;
  }

  private LearningSession session() {
    return LearningSession.create(1L, 2L, validAssignment(), NOW);
  }

  @Test
  void 유효한_배정으로_세션을_만든다() {
    LearningSession session = session();

    assertThat(session.getAssignedProblems()).hasSize(LearningSession.ITEM_COUNT);
    assertThat(session.getCreatedAt()).isEqualTo(NOW);
    assertThat(session.getCompletedAt()).isNull();
    assertThat(session.getAnalysisAppliedAt()).isNull();
  }

  @Test
  void 상태는_두_시각에서_파생한다() {
    LearningSession session = session();
    assertThat(session.status()).isEqualTo(SessionStatus.IN_PROGRESS);

    session.markCompleted(NOW.plusMinutes(10));
    assertThat(session.status()).isEqualTo(SessionStatus.ANALYSIS_PENDING);

    session.markAnalysisApplied(NOW.plusMinutes(11));
    assertThat(session.status()).isEqualTo(SessionStatus.ANALYZED);
  }

  @Test
  void 완료는_최초_한_번만_기록한다() {
    LearningSession session = session();
    session.markCompleted(NOW.plusMinutes(10));

    assertThatThrownBy(() -> session.markCompleted(NOW.plusMinutes(20)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(session.getCompletedAt()).isEqualTo(NOW.plusMinutes(10));
  }

  @Test
  void 완료되지_않은_세션은_분석을_반영할_수_없다() {
    LearningSession session = session();

    assertThatThrownBy(() -> session.markAnalysisApplied(NOW))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 분석_반영도_최초_한_번만_기록한다() {
    LearningSession session = session();
    session.markCompleted(NOW.plusMinutes(10));
    session.markAnalysisApplied(NOW.plusMinutes(11));

    assertThatThrownBy(() -> session.markAnalysisApplied(NOW.plusMinutes(12)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 배정은_정확히_10항목이어야_한다() {
    List<AssignedProblem> nineItems = validAssignment().subList(0, 9);

    assertThatThrownBy(() -> LearningSession.create(1L, 2L, nineItems, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 배정은_item_no_1부터_오름차순이어야_한다() {
    List<AssignedProblem> items = validAssignment();
    items.set(0, AssignedProblem.of(2, 101));

    assertThatThrownBy(() -> LearningSession.create(1L, 2L, items, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 서로_다른_문제가_5개가_아니면_거부한다() {
    List<AssignedProblem> items = new ArrayList<>();
    for (int i = 1; i <= LearningSession.ITEM_COUNT; i++) {
      items.add(AssignedProblem.of(i, i % 2 == 0 ? 101 : 102));
    }

    assertThatThrownBy(() -> LearningSession.create(1L, 2L, items, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 같은_문제를_연속_배정하면_거부한다() {
    List<AssignedProblem> items = new ArrayList<>();
    long[] problemIds = {101, 101, 102, 103, 104, 105, 102, 103, 104, 105};
    for (int i = 0; i < problemIds.length; i++) {
      items.add(AssignedProblem.of(i + 1, problemIds[i]));
    }

    assertThatThrownBy(() -> LearningSession.create(1L, 2L, items, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 각_문제는_정확히_두_번_배정되어야_한다() {
    List<AssignedProblem> items = new ArrayList<>();
    long[] problemIds = {101, 102, 103, 104, 105, 101, 102, 103, 104, 101};
    for (int i = 0; i < problemIds.length; i++) {
      items.add(AssignedProblem.of(i + 1, problemIds[i]));
    }

    assertThatThrownBy(() -> LearningSession.create(1L, 2L, items, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 배정_목록은_애그리거트_밖에서_바꿀_수_없다() {
    LearningSession session = session();

    assertThatThrownBy(() -> session.getAssignedProblems().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void problemId는_10진수_문자열이어야_한다() {
    assertThatThrownBy(() -> new AssignedProblem(1, "abc"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AssignedProblem(1, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(AssignedProblem.of(1, 101L).problemId()).isEqualTo("101");
  }
}
