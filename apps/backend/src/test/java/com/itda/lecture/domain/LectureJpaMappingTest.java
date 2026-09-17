package com.itda.lecture.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemSourceType;
import com.itda.problem.domain.ProblemType;
import jakarta.persistence.PersistenceException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

@DataJpaTest
class LectureJpaMappingTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 12, 0);

  @Autowired private TestEntityManager entityManager;

  @Test
  void lectures_행을_저장하고_다시_읽는다() {
    Lecture saved =
        entityManager.persistFlushFind(
            Lecture.schedule(1L, "점자 기초", "L1 안내", "004821", NOW.plusDays(1), NOW));
    entityManager.clear();

    Lecture found = entityManager.find(Lecture.class, saved.getId());

    assertThat(found.getInstructorUserId()).isEqualTo(1L);
    assertThat(found.getStatus()).isEqualTo(LectureStatus.SCHEDULED);
    // CHAR(6) 컬럼에서도 앞자리 0이 보존되어야 한다.
    assertThat(found.getParticipationCode()).isEqualTo("004821");
    assertThat(found.getScheduledAt()).isEqualTo(NOW.plusDays(1));
    assertThat(found.getCurrentLectureProblemId()).isNull();
  }

  @Test
  void lecture_problems는_수업과_문제를_ManyToOne으로_참조한다() {
    Lecture lecture =
        entityManager.persistFlushFind(Lecture.schedule(1L, "점자 기초", null, "123456", null, NOW));
    Problem problem = persistProblem("가방");

    LectureProblem saved = entityManager.persistFlushFind(new LectureProblem(lecture, problem));
    entityManager.clear();

    LectureProblem found = entityManager.find(LectureProblem.class, saved.getId());

    assertThat(found.getProblem().getId()).isEqualTo(problem.getId());
    assertThat(found.getLecture().getId()).isEqualTo(lecture.getId());
  }

  @Test
  void 같은_수업에_같은_문제는_한_번만_연결된다() {
    Lecture lecture =
        entityManager.persistFlushFind(Lecture.schedule(1L, "점자 기초", null, "123458", null, NOW));
    Problem problem = persistProblem("나비");
    entityManager.persistAndFlush(new LectureProblem(lecture, problem));

    assertThatThrownBy(() -> entityManager.persistAndFlush(new LectureProblem(lecture, problem)))
        .isInstanceOf(PersistenceException.class);
  }

  @Test
  void 같은_수업에_서로_다른_문제는_함께_연결된다() {
    Lecture lecture =
        entityManager.persistFlushFind(Lecture.schedule(1L, "점자 기초", null, "123459", null, NOW));

    LectureProblem first =
        entityManager.persistFlushFind(new LectureProblem(lecture, persistProblem("하나")));
    LectureProblem second =
        entityManager.persistFlushFind(new LectureProblem(lecture, persistProblem("둘")));

    assertThat(first.getId()).isNotEqualTo(second.getId());
    assertThat(first.getProblem().getId()).isNotEqualTo(second.getProblem().getId());
  }

  @Test
  void 현재_문제는_같은_애그리거트라도_식별자로_저장한다() {
    Lecture lecture =
        entityManager.persistFlushFind(Lecture.schedule(1L, "점자 기초", null, "123457", null, NOW));
    LectureProblem link =
        entityManager.persistFlushFind(new LectureProblem(lecture, persistProblem("사과")));

    lecture.open(NOW.plusHours(1));
    lecture.deliverProblem(link.getId());
    entityManager.flush();
    entityManager.clear();

    Lecture found = entityManager.find(Lecture.class, lecture.getId());

    assertThat(found.getStatus()).isEqualTo(LectureStatus.OPEN);
    assertThat(found.getCurrentLectureProblemId()).isEqualTo(link.getId());
  }

  @Test
  void participants는_수업_사용자_복합_PK로_한_행만_유지한다() {
    Participant participant = Participant.join(11L, 22L, NOW);
    entityManager.persistAndFlush(participant);
    entityManager.clear();

    Participant found = entityManager.find(Participant.class, ParticipantId.of(11L, 22L));

    assertThat(found).isNotNull();
    assertThat(found.getId().getLectureId()).isEqualTo(11L);
    assertThat(found.getId().getUserId()).isEqualTo(22L);
    assertThat(found.getJoinedAt()).isEqualTo(NOW);
    assertThat(found.getLeftAt()).isNull();
  }

  private Problem persistProblem(String targetText) {
    return entityManager.persistFlushFind(
        new Problem(null, ProblemSourceType.SYSTEM, ProblemType.WORD, targetText));
  }
}
