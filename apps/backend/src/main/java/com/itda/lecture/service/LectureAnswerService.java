package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.learning.domain.AttemptCell;
import com.itda.learning.domain.AttemptCellRepository;
import com.itda.learning.domain.ProblemAttempt;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.media.LectureRoom;
import com.itda.lecture.media.LiveRoomGateway;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** LIVE 답안 제출·조회 (API 명세 8.2.2절). AI 분석·BKT·user_skill_memory는 건드리지 않는다 (8.1절). */
@Slf4j
@Service
public class LectureAnswerService {

  private final LectureRepository lectureRepository;
  private final ParticipantRepository participantRepository;
  private final ProblemAttemptRepository problemAttemptRepository;
  private final AttemptCellRepository attemptCellRepository;
  private final ProblemCellRepository problemCellRepository;
  private final LectureProblemQuery problemQuery;
  private final LiveRoomGateway liveRoomGateway;
  private final TransactionOperations transactionOperations;

  public LectureAnswerService(
      LectureRepository lectureRepository,
      ParticipantRepository participantRepository,
      ProblemAttemptRepository problemAttemptRepository,
      AttemptCellRepository attemptCellRepository,
      ProblemCellRepository problemCellRepository,
      LectureProblemQuery problemQuery,
      LiveRoomGateway liveRoomGateway,
      PlatformTransactionManager transactionManager) {
    this.lectureRepository = lectureRepository;
    this.participantRepository = participantRepository;
    this.problemAttemptRepository = problemAttemptRepository;
    this.attemptCellRepository = attemptCellRepository;
    this.problemCellRepository = problemCellRepository;
    this.problemQuery = problemQuery;
    this.liveRoomGateway = liveRoomGateway;
    this.transactionOperations = new TransactionTemplate(transactionManager);
  }

  /**
   * 채점·저장을 커밋한 <b>뒤</b> 강사에게 알린다. 알림 실패는 제출 결과를 바꾸지 않는다 (API 명세 8.5.2절).
   *
   * <p>멱등 키는 저장하지 않는다. (lecture_id, user_id, problem_id) UNIQUE가 한 번만 저장하게 하고, 이미 있으면 같은 cells면 기존
   * 결과(200), 다르면 409다.
   */
  public SubmittedAnswer submit(
      AuthenticatedUser user, long lectureId, long problemId, List<Integer> cells) {
    if (user.role() != UserRole.LEARNER) {
      throw new BusinessException(ErrorCode.LEARNER_ACCESS_REQUIRED);
    }
    Submission submission;
    try {
      submission =
          transactionOperations.execute(
              tx -> submitInTransaction(user.userId(), lectureId, problemId, cells));
    } catch (DataIntegrityViolationException e) {
      // 수업 행 잠금으로 같은 학습자의 동시 제출은 이미 직렬화되지만, 잠금 밖 경로(격리 수준 차이 등)로 UNIQUE에 걸리면
      // 앞 트랜잭션은 롤백됐으므로 다시 읽어 같은 규칙(200/409)을 적용한다.
      submission =
          transactionOperations.execute(
              tx -> submitInTransaction(user.userId(), lectureId, problemId, cells));
    }
    if (submission.created()) {
      notifyInstructor(lectureId, submission.instructorUserId(), problemId);
    }
    return new SubmittedAnswer(submission.answer(), submission.created());
  }

  private Submission submitInTransaction(
      long userId, long lectureId, long problemId, List<Integer> cells) {
    // 종료 트랜잭션과 같은 행 잠금으로 직렬화한다 (API 명세 8.1절).
    Lecture lecture =
        lectureRepository.findByIdForUpdate(lectureId).orElseThrow(LectureService::notFound);
    Participant participant =
        participantRepository
            .findById(ParticipantId.of(lectureId, userId))
            .orElseThrow(LectureService::notFound);
    // 종료하면 모든 참여자의 left_at이 채워지므로 상태를 먼저 본다 (LectureMediaService와 같은 순서).
    if (!lecture.isOpen()) {
      throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
    }
    if (!participant.isParticipating()) {
      throw new BusinessException(ErrorCode.LECTURE_PARTICIPATION_REQUIRED);
    }
    if (!Long.valueOf(problemId).equals(problemQuery.currentProblemId(lecture))) {
      throw new BusinessException(ErrorCode.LECTURE_PROBLEM_NOT_CURRENT);
    }
    LiveAnswerGrader.validate(cells);

    Optional<LiveAnswer> existing = problemQuery.findAnswer(lectureId, userId, problemId);
    if (existing.isPresent()) {
      if (!LiveAnswerGrader.sameInput(cells, existing.get().cells())) {
        throw new BusinessException(ErrorCode.LECTURE_PROBLEM_ALREADY_ANSWERED);
      }
      return new Submission(existing.get(), false, lecture.getInstructorUserId());
    }

    List<Integer> expected =
        problemCellRepository.findByProblemIdOrderByCellIndexAsc(problemId).stream()
            .map(ProblemCell::getExpectedPattern)
            .toList();
    List<AnswerCell> graded = LiveAnswerGrader.grade(cells, expected);
    boolean correct = LiveAnswerGrader.isCorrect(cells, expected);
    LocalDateTime now = LectureService.now();
    ProblemAttempt attempt =
        problemAttemptRepository.save(
            ProblemAttempt.forLiveLecture(userId, problemId, lectureId, correct, now));
    attemptCellRepository.saveAll(
        graded.stream()
            .map(
                cell ->
                    new AttemptCell(
                        attempt,
                        cell.cellIndex(),
                        cell.expectedPattern(),
                        cell.inputPattern(),
                        now))
            .toList());
    LiveAnswer answer =
        new LiveAnswer(
            attempt.getId(),
            lectureId,
            problemId,
            correct,
            LiveAnswerGrader.score(correct, graded),
            now,
            graded);
    return new Submission(answer, true, lecture.getInstructorUserId());
  }

  private void notifyInstructor(long lectureId, long instructorUserId, long problemId) {
    try {
      liveRoomGateway.notifyAnswerSubmitted(
          LectureRoom.nameOf(lectureId), LectureRoom.identityOf(instructorUserId), problemId);
    } catch (RuntimeException e) {
      // 강사 웹은 출제·새로고침 때 답안 목록을 다시 조회한다. 제출은 이미 커밋됐다.
      log.warn(
          "제출 알림 실패: lecture={} problem={} ({})",
          lectureId,
          problemId,
          e.getClass().getSimpleName());
    }
  }

  private record Submission(LiveAnswer answer, boolean created, long instructorUserId) {}
}
