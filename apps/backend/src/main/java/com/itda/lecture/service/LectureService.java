package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.media.LectureRoom;
import com.itda.lecture.media.LiveRoomGateway;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LectureService {

  /** 코드 공간이 100만이라 충돌은 드물다. 이 횟수를 넘기면 코드 공간 고갈을 의심해야 한다. */
  private static final int MAX_CODE_ATTEMPTS = 10;

  private final LectureRepository lectureRepository;
  private final ParticipantRepository participantRepository;
  private final ParticipationCodeGenerator codeGenerator;
  private final LiveRoomGateway liveRoomGateway;
  private final LectureProblemQuery problemQuery;
  private final TransactionOperations transactionOperations;

  /**
   * 트랜잭션 템플릿을 모듈 안에서 만든다. {@code TransactionOperations} bean을 주입받으면 다른 모듈이 등록한 bean에 묶여, 그 모듈의 설정이
   * 바뀔 때 수업 상태 전이의 경계가 함께 바뀐다.
   */
  @Autowired
  public LectureService(
      LectureRepository lectureRepository,
      ParticipantRepository participantRepository,
      ParticipationCodeGenerator codeGenerator,
      LiveRoomGateway liveRoomGateway,
      LectureProblemQuery problemQuery,
      PlatformTransactionManager transactionManager) {
    this(
        lectureRepository,
        participantRepository,
        codeGenerator,
        liveRoomGateway,
        problemQuery,
        new TransactionTemplate(transactionManager));
  }

  /** 단위 테스트가 트랜잭션 없는 실행기를 넣는 경로. */
  LectureService(
      LectureRepository lectureRepository,
      ParticipantRepository participantRepository,
      ParticipationCodeGenerator codeGenerator,
      LiveRoomGateway liveRoomGateway,
      LectureProblemQuery problemQuery,
      TransactionOperations transactionOperations) {
    this.lectureRepository = lectureRepository;
    this.participantRepository = participantRepository;
    this.codeGenerator = codeGenerator;
    this.liveRoomGateway = liveRoomGateway;
    this.problemQuery = problemQuery;
    this.transactionOperations = transactionOperations;
  }

  @Transactional
  public LectureSnapshot create(
      AuthenticatedUser user, String title, String description, LocalDateTime scheduledAt) {
    requireInstructor(user);
    Lecture lecture =
        Lecture.schedule(user.userId(), title, description, uniqueCode(), scheduledAt, now());
    return LectureSnapshot.ofInstructor(lectureRepository.save(lecture));
  }

  @Transactional(readOnly = true)
  public Page<Lecture> listMine(AuthenticatedUser user, LectureStatus status, int page, int size) {
    requireInstructor(user);
    PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    return status == null
        ? lectureRepository.findAllByInstructorUserId(user.userId(), pageable)
        : lectureRepository.findAllByInstructorUserIdAndStatus(user.userId(), status, pageable);
  }

  @Transactional(readOnly = true)
  public LectureSnapshot find(AuthenticatedUser user, long lectureId) {
    Lecture lecture = lectureRepository.findById(lectureId).orElseThrow(LectureService::notFound);
    if (isOwner(user, lecture)) {
      return problemQuery.instructor(lecture);
    }
    if (user.role() == UserRole.LEARNER) {
      return participantRepository
          .findById(ParticipantId.of(lectureId, user.userId()))
          .map(participant -> problemQuery.learner(lecture, participant))
          .orElseThrow(LectureService::notFound);
    }
    throw notFound();
  }

  /**
   * 상태를 저장한 <b>뒤</b> 방을 조작한다. 트랜잭션 안에서 외부 호출을 하면 OpenVidu 지연이 lectures 행 잠금을 붙들고, 호출은 성공했는데 커밋이
   * 실패하면 되돌릴 수 없다. 방 조작이 실패하면 상태는 남고 503이 나가며, 강사가 같은 상태를 다시 보내면 전이는 건너뛰고 방 조작만 재시도된다 (API 명세
   * 8.5절).
   */
  public LectureSnapshot changeStatus(
      AuthenticatedUser user, long lectureId, LectureStatus target) {
    LectureSnapshot snapshot =
        transactionOperations.execute(
            tx -> problemQuery.instructor(transition(user, lectureId, target)));
    String room = LectureRoom.nameOf(lectureId);
    if (target == LectureStatus.OPEN) {
      // 같은 OPEN 재요청으로 방을 복구할 때 현재 문제도 되살린다 (API 명세 8.5.2절).
      liveRoomGateway.openRoom(room, snapshot.currentProblemId());
    } else {
      liveRoomGateway.deleteRoom(room);
    }
    return snapshot;
  }

  private Lecture transition(AuthenticatedUser user, long lectureId, LectureStatus target) {
    Lecture lecture = lectureRepository.findById(lectureId).orElseThrow(LectureService::notFound);
    if (!isOwner(user, lecture)) {
      throw notFound();
    }
    if (target == LectureStatus.SCHEDULED) {
      throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
    }
    if (lecture.getStatus() == target) {
      return lecture;
    }
    LocalDateTime now = now();
    if (target == LectureStatus.OPEN && lecture.getStatus() == LectureStatus.SCHEDULED) {
      lecture.open(now);
    } else if (target == LectureStatus.CLOSED && lecture.getStatus() == LectureStatus.OPEN) {
      lecture.close(now);
      participantRepository
          .findAllByIdLectureIdAndLeftAtIsNull(lectureId)
          .forEach(participant -> participant.leave(now));
    } else {
      throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
    }
    return lecture;
  }

  private String uniqueCode() {
    for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
      String code = codeGenerator.next();
      if (!lectureRepository.existsByParticipationCode(code)) {
        return code;
      }
    }
    throw new IllegalStateException("참여 코드를 발급하지 못했습니다.");
  }

  static boolean isOwner(AuthenticatedUser user, Lecture lecture) {
    return user.role() == UserRole.INSTRUCTOR && lecture.getInstructorUserId() == user.userId();
  }

  private static void requireInstructor(AuthenticatedUser user) {
    if (user.role() != UserRole.INSTRUCTOR) {
      throw new BusinessException(ErrorCode.INSTRUCTOR_ACCESS_REQUIRED);
    }
  }

  static BusinessException notFound() {
    return new BusinessException(ErrorCode.LECTURE_NOT_FOUND);
  }

  static LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }
}
