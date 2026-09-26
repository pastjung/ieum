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
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.UserJpaRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LectureMediaServiceTest {

  private static final long LECTURE_ID = 42L;
  private static final AuthenticatedUser INSTRUCTOR =
      new AuthenticatedUser(7L, UserRole.INSTRUCTOR);
  private static final AuthenticatedUser LEARNER = new AuthenticatedUser(8L, UserRole.LEARNER);
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 1, 0);

  @Mock private LectureRepository lectureRepository;
  @Mock private ParticipantRepository participantRepository;
  @Mock private UserJpaRepository userJpaRepository;

  private RecordingLiveRoomGateway gateway;
  private LectureMediaService service;
  private Lecture lecture;

  @BeforeEach
  void setUp() {
    gateway = new RecordingLiveRoomGateway();
    service =
        new LectureMediaService(
            lectureRepository, participantRepository, userJpaRepository, gateway);
    lecture = Lecture.schedule(7L, "약자 4회차", null, "012345", null, NOW);
    ReflectionTestUtils.setField(lecture, "id", LECTURE_ID);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(lecture));
    User teacher = Mockito.mock(User.class);
    given(teacher.getUsername()).willReturn("teacher01");
    User learner = Mockito.mock(User.class);
    given(learner.getUsername()).willReturn("learner01");
    given(userJpaRepository.findById(7L)).willReturn(Optional.of(teacher));
    given(userJpaRepository.findById(8L)).willReturn(Optional.of(learner));
  }

  private void participate(boolean active) {
    Participant participant = Participant.join(LECTURE_ID, 8L, NOW);
    if (!active) {
      participant.leave(NOW);
    }
    given(participantRepository.findById(ParticipantId.of(LECTURE_ID, 8L)))
        .willReturn(Optional.of(participant));
  }

  @Test
  void 진행_중인_수업의_강사는_송출_토큰을_받는다() {
    lecture.open(NOW);

    service.issue(INSTRUCTOR, LECTURE_ID);

    assertThat(gateway.issuedRequests)
        .containsExactly(
            new com.itda.lecture.media.LiveRoomGateway.MediaTokenRequest(
                "lecture-42", "user-7", "teacher01", true));
  }

  @Test
  void 강사가_토큰을_받을_때_방을_다시_열어_metadata를_복구한다() {
    // departure timeout으로 방이 닫혔거나 metadata 없이 자동 생성된 방이어도 강사 재접속이 OPEN 신호를 되살린다.
    lecture.open(NOW);

    service.issue(INSTRUCTOR, LECTURE_ID);

    assertThat(gateway.openedRooms).containsExactly("lecture-42");
  }

  @Test
  void 방을_다시_열지_못하면_503이고_토큰을_발급하지_않는다() {
    lecture.open(NOW);
    gateway.failRoomOperations = true;

    assertThatThrownBy(() -> service.issue(INSTRUCTOR, LECTURE_ID))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
    assertThat(gateway.issuedRequests).isEmpty();
  }

  @Test
  void 학습자_토큰_발급은_방을_조작하지_않는다() {
    participate(true);
    lecture.open(NOW);

    service.issue(LEARNER, LECTURE_ID);

    assertThat(gateway.openedRooms).isEmpty();
  }

  @Test
  void 시작_전에는_강사도_토큰을_받을_수_없다() {
    assertThatThrownBy(() -> service.issue(INSTRUCTOR, LECTURE_ID))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_LECTURE_STATE);
  }

  @Test
  void 대기_중인_학습자는_수신_토큰을_받는다() {
    participate(true);

    service.issue(LEARNER, LECTURE_ID);

    assertThat(gateway.issuedRequests.getFirst().publisher()).isFalse();
    assertThat(gateway.issuedRequests.getFirst().identity()).isEqualTo("user-8");
  }

  @Test
  void 퇴장한_학습자는_다시_입장해야_한다() {
    participate(false);

    assertThatThrownBy(() -> service.issue(LEARNER, LECTURE_ID))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LECTURE_PARTICIPATION_REQUIRED);
  }

  @Test
  void 참여한_적_없는_학습자에게는_존재를_숨긴다() {
    given(participantRepository.findById(ParticipantId.of(LECTURE_ID, 8L)))
        .willReturn(Optional.empty());

    assertThatThrownBy(() -> service.issue(LEARNER, LECTURE_ID))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LECTURE_NOT_FOUND);
  }

  @Test
  void 종료된_수업은_학습자도_토큰을_받을_수_없다() {
    // 수업 종료 시 LectureService.transition이 참여 중이던 학습자의 left_at을 채운다 (ERD 3.3절).
    // 그 상태를 그대로 반영해야 한다: 참여자는 이미 퇴장 처리됐고 수업은 CLOSED다.
    participate(false);
    lecture.open(NOW);
    lecture.close(NOW);

    assertThatThrownBy(() -> service.issue(LEARNER, LECTURE_ID))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_LECTURE_STATE);
  }
}
