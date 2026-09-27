package com.itda.lecture.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.lecture.service.RecordingLiveRoomGateway.SpeakingChange;
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
class LectureSpeakingServiceTest {

  private static final long LECTURE_ID = 42L;
  private static final long LEARNER_ID = 8L;
  private static final AuthenticatedUser INSTRUCTOR =
      new AuthenticatedUser(7L, UserRole.INSTRUCTOR);
  private static final AuthenticatedUser OTHER_INSTRUCTOR =
      new AuthenticatedUser(9L, UserRole.INSTRUCTOR);
  private static final AuthenticatedUser LEARNER =
      new AuthenticatedUser(LEARNER_ID, UserRole.LEARNER);
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 1, 0);

  @Mock private LectureRepository lectureRepository;
  @Mock private ParticipantRepository participantRepository;

  private RecordingLiveRoomGateway gateway;
  private LectureSpeakingService service;
  private Lecture lecture;

  @BeforeEach
  void setUp() {
    gateway = new RecordingLiveRoomGateway();
    service = new LectureSpeakingService(lectureRepository, participantRepository, gateway);
    lecture = scheduledLecture();
    lecture.open(NOW);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(lecture));
    participate(true);
  }

  private static Lecture scheduledLecture() {
    Lecture scheduled = Lecture.schedule(7L, "약자 4회차", null, "012345", null, NOW);
    ReflectionTestUtils.setField(scheduled, "id", LECTURE_ID);
    return scheduled;
  }

  private void participate(boolean active) {
    Participant participant = Participant.join(LECTURE_ID, LEARNER_ID, NOW);
    if (!active) {
      participant.leave(NOW);
    }
    given(participantRepository.findById(ParticipantId.of(LECTURE_ID, LEARNER_ID)))
        .willReturn(Optional.of(participant));
  }

  private void assertRejected(AuthenticatedUser user, ErrorCode expected) {
    assertThatThrownBy(() -> service.changeSpeaking(user, LECTURE_ID, LEARNER_ID, true))
        .extracting("errorCode")
        .isEqualTo(expected);
    assertThat(gateway.speakingChanges).isEmpty();
  }

  @Test
  void 담당_강사가_활성_참여자의_발언을_허용한다() {
    service.changeSpeaking(INSTRUCTOR, LECTURE_ID, LEARNER_ID, true);

    assertThat(gateway.speakingChanges)
        .containsExactly(new SpeakingChange("lecture-42", "user-8", true));
  }

  @Test
  void 담당_강사가_발언을_회수한다() {
    service.changeSpeaking(INSTRUCTOR, LECTURE_ID, LEARNER_ID, false);

    assertThat(gateway.speakingChanges)
        .containsExactly(new SpeakingChange("lecture-42", "user-8", false));
  }

  @Test
  void 강사가_아니면_403이다() {
    assertRejected(LEARNER, ErrorCode.INSTRUCTOR_ACCESS_REQUIRED);
  }

  @Test
  void 수업이_없으면_404다() {
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.empty());

    assertRejected(INSTRUCTOR, ErrorCode.LECTURE_NOT_FOUND);
  }

  @Test
  void 담당이_아닌_강사에게는_수업_존재를_숨긴다() {
    assertRejected(OTHER_INSTRUCTOR, ErrorCode.LECTURE_NOT_FOUND);
  }

  @Test
  void 시작_전_수업에서는_409다() {
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(scheduledLecture()));

    assertRejected(INSTRUCTOR, ErrorCode.INVALID_LECTURE_STATE);
  }

  @Test
  void 종료된_수업에서는_409다() {
    lecture.close(NOW);

    assertRejected(INSTRUCTOR, ErrorCode.INVALID_LECTURE_STATE);
  }

  @Test
  void 참여한_적_없는_사용자는_대상이_아니다() {
    given(participantRepository.findById(ParticipantId.of(LECTURE_ID, LEARNER_ID)))
        .willReturn(Optional.empty());

    assertRejected(INSTRUCTOR, ErrorCode.LECTURE_PARTICIPANT_NOT_FOUND);
  }

  @Test
  void 퇴장한_학습자는_대상이_아니다() {
    participate(false);

    assertRejected(INSTRUCTOR, ErrorCode.LECTURE_PARTICIPANT_NOT_FOUND);
  }

  @Test
  void 음성_방에_접속해_있지_않으면_404다() {
    gateway.participantDisconnected = true;

    assertRejected(INSTRUCTOR, ErrorCode.LECTURE_PARTICIPANT_NOT_CONNECTED);
  }

  @Test
  void OpenVidu_호출이_실패하면_503이다() {
    gateway.failRoomOperations = true;

    assertRejected(INSTRUCTOR, ErrorCode.MEDIA_SERVER_UNAVAILABLE);
  }
}
