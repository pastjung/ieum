package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.media.LectureRoom;
import com.itda.lecture.media.LiveRoomGateway;
import com.itda.lecture.media.LiveRoomGateway.IssuedMediaToken;
import com.itda.lecture.media.LiveRoomGateway.MediaTokenRequest;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 음성 토큰 발급 권한 판정 (API 명세 8.5절). 토큰은 저장하지 않는다. */
@Service
@RequiredArgsConstructor
public class LectureMediaService {

  private final LectureRepository lectureRepository;
  private final ParticipantRepository participantRepository;
  private final UserJpaRepository userJpaRepository;
  private final LiveRoomGateway liveRoomGateway;

  @Transactional(readOnly = true)
  public IssuedMediaToken issue(AuthenticatedUser user, long lectureId) {
    Lecture lecture = lectureRepository.findById(lectureId).orElseThrow(LectureService::notFound);
    boolean publisher;
    if (LectureService.isOwner(user, lecture)) {
      if (!lecture.isOpen()) {
        throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
      }
      publisher = true;
    } else if (user.role() == UserRole.LEARNER) {
      Participant participant =
          participantRepository
              .findById(ParticipantId.of(lectureId, user.userId()))
              .orElseThrow(LectureService::notFound);
      // 수업 종료 시 참여 중이던 학습자는 모두 left_at이 채워진다 (LectureService.transition).
      // 그래서 CLOSED 수업의 재요청은 상태 판정(409)이 먼저여야 한다 — 순서를 바꾸면 모든 학습자가
      // 퇴장 요구(403)로 가려져 종료 사실을 알 수 없다 (API 명세 8.5절).
      if (!lecture.isJoinable()) {
        throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
      }
      if (!participant.isParticipating()) {
        throw new BusinessException(ErrorCode.LECTURE_PARTICIPATION_REQUIRED);
      }
      publisher = false;
    } else {
      throw LectureService.notFound();
    }
    return liveRoomGateway.issueToken(
        new MediaTokenRequest(
            LectureRoom.nameOf(lectureId),
            LectureRoom.identityOf(user.userId()),
            displayName(user.userId()),
            publisher));
  }

  private String displayName(long userId) {
    return userJpaRepository
        .findById(userId)
        .map(found -> found.getUsername())
        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
  }
}
