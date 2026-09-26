package com.itda.lecture.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DataJpaTest
class LectureRepositoryTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 1, 0);

  @Autowired private LectureRepository lectureRepository;
  @Autowired private ParticipantRepository participantRepository;

  private Lecture save(long instructorId, String code) {
    return lectureRepository.save(Lecture.schedule(instructorId, "약자 4회차", null, code, null, NOW));
  }

  @Test
  void 참여_코드로_수업을_찾는다() {
    Lecture saved = save(1L, "012345");

    assertThat(lectureRepository.findByParticipationCode("012345")).get().isEqualTo(saved);
    assertThat(lectureRepository.existsByParticipationCode("012345")).isTrue();
    assertThat(lectureRepository.existsByParticipationCode("999999")).isFalse();
  }

  @Test
  void 강사_본인_수업만_상태별로_페이지_조회한다() {
    save(1L, "000001");
    Lecture opened = save(1L, "000002");
    opened.open(NOW);
    save(2L, "000003");
    PageRequest page = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

    Page<Lecture> all = lectureRepository.findAllByInstructorUserId(1L, page);
    Page<Lecture> open =
        lectureRepository.findAllByInstructorUserIdAndStatus(1L, LectureStatus.OPEN, page);

    assertThat(all.getTotalElements()).isEqualTo(2);
    assertThat(open.getContent()).containsExactly(opened);
  }

  @Test
  void 수업의_활성_참여자만_찾는다() {
    Lecture lecture = save(1L, "000004");
    Participant active = participantRepository.save(Participant.join(lecture.getId(), 10L, NOW));
    Participant left = participantRepository.save(Participant.join(lecture.getId(), 11L, NOW));
    left.leave(NOW);
    participantRepository.flush();

    List<Participant> found =
        participantRepository.findAllByIdLectureIdAndLeftAtIsNull(lecture.getId());

    assertThat(found).extracting(Participant::getId).containsExactly(active.getId());
    assertThat(participantRepository.findById(ParticipantId.of(lecture.getId(), 11L))).isPresent();
  }
}
