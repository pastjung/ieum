package com.itda.lecture.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LectureTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 12, 0);

  private Lecture scheduled() {
    return Lecture.schedule(1L, "점자 기초", "L1 안내", "004821", NOW.plusDays(1), NOW);
  }

  @Test
  void 생성_직후에는_SCHEDULED이고_시작_종료_시각이_없다() {
    Lecture lecture = scheduled();

    assertThat(lecture.getStatus()).isEqualTo(LectureStatus.SCHEDULED);
    assertThat(lecture.getStartedAt()).isNull();
    assertThat(lecture.getEndedAt()).isNull();
    assertThat(lecture.getCurrentLectureProblemId()).isNull();
  }

  @Test
  void 참여_코드의_앞자리_0을_보존한다() {
    assertThat(scheduled().getParticipationCode()).isEqualTo("004821");
  }

  @ParameterizedTest
  @ValueSource(strings = {"12345", "1234567", "12a456"})
  void 참여_코드가_숫자_6자리가_아니면_거부한다(String code) {
    assertThatThrownBy(() -> Lecture.schedule(1L, "제목", null, code, null, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 상태는_SCHEDULED에서_OPEN을_거쳐_CLOSED로만_이동한다() {
    Lecture lecture = scheduled();

    lecture.open(NOW.plusDays(1));
    assertThat(lecture.getStatus()).isEqualTo(LectureStatus.OPEN);
    assertThat(lecture.getStartedAt()).isEqualTo(NOW.plusDays(1));

    lecture.close(NOW.plusDays(1).plusHours(1));
    assertThat(lecture.getStatus()).isEqualTo(LectureStatus.CLOSED);
    assertThat(lecture.getEndedAt()).isEqualTo(NOW.plusDays(1).plusHours(1));
  }

  @Test
  void 종료된_수업은_다시_시작하거나_종료할_수_없다() {
    Lecture lecture = scheduled();
    lecture.open(NOW);
    lecture.close(NOW.plusHours(1));

    assertThatThrownBy(() -> lecture.open(NOW)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> lecture.close(NOW)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 종료하면_현재_문제를_NULL로_만든다() {
    Lecture lecture = scheduled();
    lecture.open(NOW);
    lecture.deliverProblem(55L);
    assertThat(lecture.getCurrentLectureProblemId()).isEqualTo(55L);

    lecture.close(NOW.plusHours(1));

    assertThat(lecture.getCurrentLectureProblemId()).isNull();
  }

  @Test
  void 진행_중이_아니면_문제를_전달할_수_없다() {
    Lecture lecture = scheduled();

    assertThatThrownBy(() -> lecture.deliverProblem(55L)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 제목_설명_예정_시각_수정은_SCHEDULED에서만_허용한다() {
    Lecture lecture = scheduled();
    lecture.modifyPlan("새 제목", null, NOW.plusDays(2));
    assertThat(lecture.getTitle()).isEqualTo("새 제목");
    assertThat(lecture.getDescription()).isNull();

    lecture.open(NOW);

    assertThatThrownBy(() -> lecture.modifyPlan("또 다른 제목", null, null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void CLOSED가_아니면_참여할_수_있다() {
    Lecture lecture = scheduled();
    assertThat(lecture.isJoinable()).isTrue();

    lecture.open(NOW);
    assertThat(lecture.isJoinable()).isTrue();

    lecture.close(NOW.plusHours(1));
    assertThat(lecture.isJoinable()).isFalse();
  }

  @Test
  void 수업_문제_연결은_수업과_문제를_모두_참조한다() {
    Lecture lecture = scheduled();
    Problem problem = new Problem(Level.WORD, "가방");

    LectureProblem link = new LectureProblem(lecture, problem);

    assertThat(link.getLecture()).isSameAs(lecture);
    assertThat(link.getProblem()).isSameAs(problem);
  }

  @Test
  void 수업_없이는_문제를_연결할_수_없다() {
    Problem problem = new Problem(Level.WORD, "가방");

    assertThatThrownBy(() -> new LectureProblem(null, problem))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 문제_없이는_수업에_연결할_수_없다() {
    assertThatThrownBy(() -> new LectureProblem(scheduled(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 재입장은_참여_시각을_유지하고_퇴장_시각만_되돌린다() {
    Participant participant = Participant.join(1L, 2L, NOW);
    participant.leave(NOW.plusMinutes(10));
    assertThat(participant.isParticipating()).isFalse();

    participant.rejoin();

    assertThat(participant.isParticipating()).isTrue();
    assertThat(participant.getJoinedAt()).isEqualTo(NOW);
    assertThat(participant.getLeftAt()).isNull();
  }
}
