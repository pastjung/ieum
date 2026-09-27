package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureProblem;
import com.itda.lecture.media.LectureRoom;
import com.itda.lecture.media.LiveRoomGateway;
import com.itda.lecture.repository.LectureProblemRepository;
import com.itda.lecture.repository.LectureRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** 현재 문제 출제 (API 명세 8.2·8.5.2절). */
@Service
public class LectureDeliveryService {

  private final LectureRepository lectureRepository;
  private final LectureProblemRepository lectureProblemRepository;
  private final LectureProblemQuery problemQuery;
  private final LiveRoomGateway liveRoomGateway;
  private final TransactionOperations transactionOperations;

  public LectureDeliveryService(
      LectureRepository lectureRepository,
      LectureProblemRepository lectureProblemRepository,
      LectureProblemQuery problemQuery,
      LiveRoomGateway liveRoomGateway,
      PlatformTransactionManager transactionManager) {
    this.lectureRepository = lectureRepository;
    this.lectureProblemRepository = lectureProblemRepository;
    this.problemQuery = problemQuery;
    this.liveRoomGateway = liveRoomGateway;
    this.transactionOperations = new TransactionTemplate(transactionManager);
  }

  /**
   * 저장을 커밋한 <b>뒤</b> 방 metadata를 쓴다 — {@link LectureService#changeStatus}와 같은 이유다. metadata 쓰기가
   * 실패하면 503이 나가고 저장은 남는다. 강사가 같은 문제로 다시 부르면 DB는 그대로 두고 metadata만 다시 쓴다.
   */
  public LectureSnapshot deliver(AuthenticatedUser user, long lectureId, long problemId) {
    LectureSnapshot snapshot =
        transactionOperations.execute(tx -> deliverInTransaction(user, lectureId, problemId));
    liveRoomGateway.openRoom(LectureRoom.nameOf(lectureId), problemId);
    return snapshot;
  }

  private LectureSnapshot deliverInTransaction(
      AuthenticatedUser user, long lectureId, long problemId) {
    // 종료와 겹치면 CLOSED 수업에 현재 문제가 남는다 — 행을 잠가 직렬화한다 (8.1절).
    Lecture lecture =
        lectureRepository.findByIdForUpdate(lectureId).orElseThrow(LectureService::notFound);
    if (!LectureService.isOwner(user, lecture)) {
      throw LectureService.notFound();
    }
    if (!lecture.isOpen()) {
      throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
    }
    LectureProblem link =
        lectureProblemRepository
            .findByLecture_IdAndProblem_Id(lectureId, problemId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PROBLEM_NOT_FOUND));
    if (!link.getId().equals(lecture.getCurrentLectureProblemId())) {
      lecture.deliverProblem(link.getId());
    }
    return problemQuery.instructor(lecture);
  }
}
