package com.itda.lecture.dto;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.service.LectureSnapshot;
import com.itda.problem.dto.LinkedProblemResponse;
import java.util.List;

/** 담당 강사용 Snapshot (API 명세 8.2절). currentProblem·linkedProblems는 정답 점형을 포함한다. */
public record InstructorLectureResponse(
    String lectureId,
    String title,
    String description,
    LectureStatus status,
    String scheduledAt,
    String startedAt,
    String endedAt,
    LinkedProblemResponse currentProblem,
    String createdAt,
    String participationCode,
    List<LinkedProblemResponse> linkedProblems) {

  public static InstructorLectureResponse from(LectureSnapshot snapshot) {
    Lecture lecture = snapshot.lecture();
    return new InstructorLectureResponse(
        String.valueOf(lecture.getId()),
        lecture.getTitle(),
        lecture.getDescription(),
        lecture.getStatus(),
        UtcTimes.format(lecture.getScheduledAt()),
        UtcTimes.format(lecture.getStartedAt()),
        UtcTimes.format(lecture.getEndedAt()),
        snapshot.currentProblem() == null
            ? null
            : LinkedProblemResponse.from(snapshot.currentProblem()),
        UtcTimes.format(lecture.getCreatedAt()),
        lecture.getParticipationCode(),
        snapshot.linkedProblems().stream().map(LinkedProblemResponse::from).toList());
  }
}
