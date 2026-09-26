package com.itda.lecture.dto;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.service.LectureSnapshot;
import java.util.List;

/**
 * 담당 강사용 Snapshot (API 명세 8.2절). currentProblem·linkedProblems는 문제 출제를 붙일 때 채운다 — MVP에서는 null과 빈
 * 배열이다.
 */
public record InstructorLectureResponse(
    String lectureId,
    String title,
    String description,
    LectureStatus status,
    String scheduledAt,
    String startedAt,
    String endedAt,
    Object currentProblem,
    String createdAt,
    String participationCode,
    List<Object> linkedProblems) {

  public static InstructorLectureResponse from(LectureSnapshot snapshot) {
    return from(snapshot.lecture());
  }

  public static InstructorLectureResponse from(Lecture lecture) {
    return new InstructorLectureResponse(
        String.valueOf(lecture.getId()),
        lecture.getTitle(),
        lecture.getDescription(),
        lecture.getStatus(),
        UtcTimes.format(lecture.getScheduledAt()),
        UtcTimes.format(lecture.getStartedAt()),
        UtcTimes.format(lecture.getEndedAt()),
        null,
        UtcTimes.format(lecture.getCreatedAt()),
        lecture.getParticipationCode(),
        List.of());
  }
}
