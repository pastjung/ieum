package com.itda.lecture.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LectureParticipationServiceTest {

  private static final long LECTURE_ID = 42L;
  private static final AuthenticatedUser LEARNER = new AuthenticatedUser(8L, UserRole.LEARNER);
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 1, 0);

  @Mock private LectureRepository lectureRepository;
  @Mock private ParticipantRepository participantRepository;

  private LectureParticipationService service;
  private Lecture lecture;

  @BeforeEach
  void setUp() {
    service = new LectureParticipationService(lectureRepository, participantRepository);
    lecture = Lecture.schedule(7L, "약자 4회차", null, "012345", null, NOW);
    ReflectionTestUtils.setField(lecture, "id", LECTURE_ID);
    given(lectureRepository.findByParticipationCode("012345")).willReturn(Optional.of(lecture));
    given(participantRepository.save(any(Participant.class)))
        .willAnswer(call -> call.getArgument(0));
  }

  @Test
  void 처음_입장하면_참여_행을_만든다() {
    given(participantRepository.findById(ParticipantId.of(LECTURE_ID, 8L)))
        .willReturn(Optional.empty());

    LectureParticipationService.ParticipationResult result = service.join(LEARNER, "012345");

    assertThat(result.firstJoin()).isTrue();
    assertThat(result.snapshot().participation().isParticipating()).isTrue();
    assertThat(result.snapshot().lecture().getId()).isEqualTo(LECTURE_ID);
  }

  @Test
  void 퇴장했던_학습자가_다시_들어오면_joinedAt을_유지한다() {
    Participant existing = Participant.join(LECTURE_ID, 8L, NOW.minusHours(1));
    existing.leave(NOW);
    given(participantRepository.findById(ParticipantId.of(LECTURE_ID, 8L)))
        .willReturn(Optional.of(existing));

    LectureParticipationService.ParticipationResult result = service.join(LEARNER, "012345");

    assertThat(result.firstJoin()).isFalse();
    assertThat(existing.isParticipating()).isTrue();
    assertThat(existing.getJoinedAt()).isEqualTo(NOW.minusHours(1));
  }

  @Test
  void 없는_코드와_종료된_수업은_구분하지_않고_거부한다() {
    lecture.open(NOW);
    lecture.close(NOW);

    assertThatThrownBy(() -> service.join(LEARNER, "012345"))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LECTURE_NOT_JOINABLE);
    assertThatThrownBy(() -> service.join(LEARNER, "999999"))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LECTURE_NOT_JOINABLE);
  }

  @Test
  void 강사는_코드로_입장할_수_없다() {
    assertThatThrownBy(() -> service.join(new AuthenticatedUser(7L, UserRole.INSTRUCTOR), "012345"))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LEARNER_ACCESS_REQUIRED);
  }
}
