package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.user.domain.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LectureParticipationService {

  private final LectureRepository lectureRepository;
  private final ParticipantRepository participantRepository;

  /**
   * 코드로 입장한다. 최초 입장이면 참여 행을 만들고, 재입장이면 joined_at을 유지한 채 left_at만 되돌린다 (API 명세 8.1절).
   *
   * <p>같은 학습자의 최초 입장이 동시에 두 번 오면 복합 PK 충돌로 한쪽이 실패한다. MVP에서는 재시도(재입장 200)로 충분하다.
   */
  @Transactional
  public ParticipationResult join(AuthenticatedUser user, String participationCode) {
    if (user.role() != UserRole.LEARNER) {
      throw new BusinessException(ErrorCode.LEARNER_ACCESS_REQUIRED);
    }
    Lecture lecture =
        lectureRepository
            .findByParticipationCode(participationCode)
            .filter(Lecture::isJoinable)
            .orElseThrow(() -> new BusinessException(ErrorCode.LECTURE_NOT_JOINABLE));

    ParticipantId id = ParticipantId.of(lecture.getId(), user.userId());
    return participantRepository
        .findById(id)
        .map(
            existing -> {
              existing.rejoin();
              return new ParticipationResult(LectureSnapshot.ofLearner(lecture, existing), false);
            })
        .orElseGet(
            () -> {
              Participant joined =
                  participantRepository.save(
                      Participant.join(lecture.getId(), user.userId(), LectureService.now()));
              return new ParticipationResult(LectureSnapshot.ofLearner(lecture, joined), true);
            });
  }

  /**
   * @param firstJoin true면 201, false면 200 (API 명세 8.2절)
   */
  public record ParticipationResult(LectureSnapshot snapshot, boolean firstJoin) {}
}
