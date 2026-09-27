package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.media.LectureRoom;
import com.itda.lecture.media.LiveRoomGateway;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.user.domain.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 강사의 학습자 발언 허용·회수 (API 명세 8.5.1절). 상태는 OpenVidu 참가자 권한·속성뿐이고 DB에 저장하지 않는다.
 *
 * <p>트랜잭션을 두지 않는다. 쓰기가 없고, OpenVidu 호출 동안 DB 연결을 붙들 이유가 없다(open-in-view는 꺼져 있다).
 */
@Service
@RequiredArgsConstructor
public class LectureSpeakingService {

  private final LectureRepository lectureRepository;
  private final ParticipantRepository participantRepository;
  private final LiveRoomGateway liveRoomGateway;

  /** 판정 순서는 명세 오류 표 순서와 같다 — 역할, 소유, 상태, 참여, 접속. */
  public void changeSpeaking(
      AuthenticatedUser user, long lectureId, long learnerUserId, boolean allowed) {
    if (user.role() != UserRole.INSTRUCTOR) {
      throw new BusinessException(ErrorCode.INSTRUCTOR_ACCESS_REQUIRED);
    }
    Lecture lecture = lectureRepository.findById(lectureId).orElseThrow(LectureService::notFound);
    if (!LectureService.isOwner(user, lecture)) {
      throw LectureService.notFound();
    }
    if (!lecture.isOpen()) {
      throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
    }
    boolean participating =
        participantRepository
            .findById(ParticipantId.of(lectureId, learnerUserId))
            .map(Participant::isParticipating)
            .orElse(false);
    if (!participating) {
      throw new BusinessException(ErrorCode.LECTURE_PARTICIPANT_NOT_FOUND);
    }
    boolean connected =
        liveRoomGateway.setSpeaking(
            LectureRoom.nameOf(lectureId), LectureRoom.identityOf(learnerUserId), allowed);
    if (!connected) {
      throw new BusinessException(ErrorCode.LECTURE_PARTICIPANT_NOT_CONNECTED);
    }
  }
}
