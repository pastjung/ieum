package com.itda.lecture.service;

import com.itda.learning.domain.AttemptCellRepository;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureProblem;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.repository.LectureProblemRepository;
import com.itda.problem.domain.Problem;
import com.itda.problem.service.LinkedProblem;
import com.itda.problem.service.LinkedProblemReader;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 수업의 문제 쪽 상태를 읽는다 — Snapshot 조립, 현재 문제, 저장된 LIVE 답안 (API 명세 8.2절). 문제는 지연 로딩이므로 호출자의 트랜잭션 안에서 부른다.
 */
@Component
@RequiredArgsConstructor
public class LectureProblemQuery {

  private final LectureProblemRepository lectureProblemRepository;
  private final ProblemAttemptRepository problemAttemptRepository;
  private final AttemptCellRepository attemptCellRepository;
  private final LinkedProblemReader linkedProblemReader;

  public LectureSnapshot instructor(Lecture lecture) {
    List<LectureProblem> links =
        lectureProblemRepository.findAllWithProblemByLectureId(lecture.getId());
    List<LinkedProblem> linked =
        linkedProblemReader.readAll(links.stream().map(LectureProblem::getProblem).toList());
    LinkedProblem current = null;
    for (int i = 0; i < links.size(); i++) {
      if (links.get(i).getId().equals(lecture.getCurrentLectureProblemId())) {
        current = linked.get(i);
      }
    }
    return LectureSnapshot.ofInstructor(lecture, current, linked);
  }

  /** 정답 점형은 LinkedProblem에 있지만 학습자 DTO가 내보내지 않는다. */
  public LectureSnapshot learner(Lecture lecture, Participant participant) {
    Optional<Problem> current = currentProblem(lecture);
    if (current.isEmpty()) {
      return LectureSnapshot.ofLearner(lecture, participant);
    }
    Problem problem = current.get();
    LiveAnswer myResult =
        findAnswer(lecture.getId(), participant.getId().getUserId(), problem.getId()).orElse(null);
    return LectureSnapshot.ofLearner(
        lecture, participant, linkedProblemReader.read(problem), myResult);
  }

  /** 현재 문제의 problems.id. 없으면 null. */
  public Long currentProblemId(Lecture lecture) {
    return currentProblem(lecture).map(Problem::getId).orElse(null);
  }

  public Optional<LiveAnswer> findAnswer(long lectureId, long userId, long problemId) {
    return problemAttemptRepository
        .findByLectureIdAndUserIdAndProblemId(lectureId, userId, problemId)
        .map(
            attempt ->
                LiveAnswer.of(
                    attempt, attemptCellRepository.findAllByAttemptIds(List.of(attempt.getId()))));
  }

  private Optional<Problem> currentProblem(Lecture lecture) {
    Long lectureProblemId = lecture.getCurrentLectureProblemId();
    if (lectureProblemId == null) {
      return Optional.empty();
    }
    return lectureProblemRepository.findById(lectureProblemId).map(LectureProblem::getProblem);
  }
}
