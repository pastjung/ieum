package com.itda.lecture.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.repository.LectureProblemRepository;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LectureServiceTest {

  private static final long LECTURE_ID = 42L;
  private static final AuthenticatedUser INSTRUCTOR =
      new AuthenticatedUser(7L, UserRole.INSTRUCTOR);
  private static final AuthenticatedUser OTHER_INSTRUCTOR =
      new AuthenticatedUser(9L, UserRole.INSTRUCTOR);
  private static final AuthenticatedUser LEARNER = new AuthenticatedUser(8L, UserRole.LEARNER);
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 1, 0);

  @Mock private LectureRepository lectureRepository;
  @Mock private ParticipantRepository participantRepository;
  @Mock private LectureProblemRepository lectureProblemRepository;
  @Mock private ProblemAttemptRepository problemAttemptRepository;
  @Mock private ParticipationCodeGenerator codeGenerator;
  @Mock private LectureProblemQuery problemQuery;

  private RecordingLiveRoomGateway gateway;
  private LectureService service;

  @BeforeEach
  void setUp() {
    gateway = new RecordingLiveRoomGateway();
    service =
        new LectureService(
            lectureRepository,
            participantRepository,
            lectureProblemRepository,
            problemAttemptRepository,
            codeGenerator,
            gateway,
            problemQuery,
            TransactionOperations.withoutTransaction());
    given(lectureRepository.save(any(Lecture.class))).willAnswer(call -> call.getArgument(0));
    // 문제 쪽 조립은 LectureProblemQuery가 맡는다. 여기서는 수업 상태만 본다.
    given(problemQuery.instructor(any(Lecture.class)))
        .willAnswer(call -> LectureSnapshot.ofInstructor(call.getArgument(0)));
    given(problemQuery.learner(any(Lecture.class), any(Participant.class)))
        .willAnswer(call -> LectureSnapshot.ofLearner(call.getArgument(0), call.getArgument(1)));
  }

  private Lecture lecture(long instructorId) {
    Lecture lecture = Lecture.schedule(instructorId, "약자 4회차", null, "012345", null, NOW);
    ReflectionTestUtils.setField(lecture, "id", LECTURE_ID);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(lecture));
    return lecture;
  }

  @Test
  void 강사가_만들면_SCHEDULED와_중복되지_않는_코드를_받는다() {
    given(codeGenerator.next()).willReturn("111111", "222222");
    given(lectureRepository.existsByParticipationCode("111111")).willReturn(true);
    given(lectureRepository.existsByParticipationCode("222222")).willReturn(false);

    LectureSnapshot created = service.create(INSTRUCTOR, "약자 4회차", "설명", null);

    assertThat(created.isInstructorView()).isTrue();
    assertThat(created.lecture().getStatus()).isEqualTo(LectureStatus.SCHEDULED);
    assertThat(created.lecture().getParticipationCode()).isEqualTo("222222");
    assertThat(created.lecture().getInstructorUserId()).isEqualTo(7L);
  }

  @Test
  void 학습자는_수업을_만들_수_없다() {
    assertThatThrownBy(() -> service.create(LEARNER, "제목", null, null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INSTRUCTOR_ACCESS_REQUIRED);
  }

  @Test
  void 담당_강사는_강사_보기로_조회한다() {
    lecture(7L);

    assertThat(service.find(INSTRUCTOR, LECTURE_ID).isInstructorView()).isTrue();
  }

  @Test
  void 참여한_학습자는_학습자_보기로_조회한다() {
    lecture(7L);
    Participant participant = Participant.join(LECTURE_ID, 8L, NOW);
    given(participantRepository.findById(ParticipantId.of(LECTURE_ID, 8L)))
        .willReturn(Optional.of(participant));

    LectureSnapshot snapshot = service.find(LEARNER, LECTURE_ID);

    assertThat(snapshot.isInstructorView()).isFalse();
    assertThat(snapshot.participation()).isSameAs(participant);
  }

  @Test
  void 다른_강사와_참여하지_않은_학습자에게는_존재를_숨긴다() {
    lecture(7L);
    given(participantRepository.findById(any())).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.find(OTHER_INSTRUCTOR, LECTURE_ID))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LECTURE_NOT_FOUND);
    assertThatThrownBy(() -> service.find(LEARNER, LECTURE_ID))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LECTURE_NOT_FOUND);
  }

  @Test
  void 시작하면_OPEN이_되고_방을_연다() {
    Lecture lecture = lecture(7L);

    service.changeStatus(INSTRUCTOR, LECTURE_ID, LectureStatus.OPEN);

    assertThat(lecture.getStatus()).isEqualTo(LectureStatus.OPEN);
    assertThat(lecture.getStartedAt()).isNotNull();
    assertThat(gateway.openedRooms).containsExactly("lecture-42");
  }

  @Test
  void 종료하면_활성_참여자를_퇴장시키고_방을_삭제한다() {
    Lecture lecture = lecture(7L);
    lecture.open(NOW);
    Participant active = Participant.join(LECTURE_ID, 8L, NOW);
    given(participantRepository.findAllByIdLectureIdAndLeftAtIsNull(LECTURE_ID))
        .willReturn(List.of(active));

    service.changeStatus(INSTRUCTOR, LECTURE_ID, LectureStatus.CLOSED);

    assertThat(lecture.getStatus()).isEqualTo(LectureStatus.CLOSED);
    assertThat(active.isParticipating()).isFalse();
    assertThat(gateway.deletedRooms).containsExactly("lecture-42");
  }

  @Test
  void 같은_상태_재요청은_상태를_바꾸지_않고_방_작업만_다시_한다() {
    Lecture lecture = lecture(7L);
    lecture.open(NOW);
    LocalDateTime startedAt = lecture.getStartedAt();

    service.changeStatus(INSTRUCTOR, LECTURE_ID, LectureStatus.OPEN);

    assertThat(lecture.getStartedAt()).isEqualTo(startedAt);
    assertThat(gateway.openedRooms).containsExactly("lecture-42");
  }

  @Test
  void 종료된_수업의_종료_재요청은_종료_시각을_유지하고_방_삭제만_다시_한다() {
    Lecture lecture = lecture(7L);
    lecture.open(NOW);
    lecture.close(NOW.plusHours(1));
    LocalDateTime endedAt = lecture.getEndedAt();

    service.changeStatus(INSTRUCTOR, LECTURE_ID, LectureStatus.CLOSED);

    assertThat(lecture.getStatus()).isEqualTo(LectureStatus.CLOSED);
    assertThat(lecture.getEndedAt()).isEqualTo(endedAt);
    assertThat(gateway.deletedRooms).containsExactly("lecture-42");
  }

  @Test
  void 건너뛰거나_되돌리는_전이는_409다() {
    lecture(7L);

    assertThatThrownBy(() -> service.changeStatus(INSTRUCTOR, LECTURE_ID, LectureStatus.CLOSED))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_LECTURE_STATE);
    assertThatThrownBy(() -> service.changeStatus(INSTRUCTOR, LECTURE_ID, LectureStatus.SCHEDULED))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_LECTURE_STATE);
    assertThat(gateway.openedRooms).isEmpty();
    assertThat(gateway.deletedRooms).isEmpty();
  }

  @Test
  void 방_작업이_실패해도_상태는_저장된_채_503이다() {
    Lecture lecture = lecture(7L);
    gateway.failRoomOperations = true;

    assertThatThrownBy(() -> service.changeStatus(INSTRUCTOR, LECTURE_ID, LectureStatus.OPEN))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
    assertThat(lecture.getStatus()).isEqualTo(LectureStatus.OPEN);
  }

  @Test
  void 다른_강사는_상태를_바꿀_수_없다() {
    lecture(7L);

    assertThatThrownBy(() -> service.changeStatus(OTHER_INSTRUCTOR, LECTURE_ID, LectureStatus.OPEN))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LECTURE_NOT_FOUND);
  }

  @Test
  void 시작하면_현재_문제를_방_metadata에_싣는다() {
    Lecture lecture = lecture(7L);
    given(problemQuery.instructor(lecture))
        .willAnswer(
            call ->
                LectureSnapshot.ofInstructor(
                    lecture,
                    new com.itda.problem.service.LinkedProblem(
                        26578L, "ㄱ", "초성 ㄱ", com.itda.problem.domain.Level.CONSONANT, List.of(8)),
                    List.of()));

    service.changeStatus(
        new AuthenticatedUser(7L, UserRole.INSTRUCTOR), LECTURE_ID, LectureStatus.OPEN);

    assertThat(gateway.openedCurrentProblemIds).containsExactly(26578L);
  }
}
