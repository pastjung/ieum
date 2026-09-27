package com.itda.lecture.dto;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.service.LectureListItem;
import com.itda.problem.dto.LinkedProblemResponse;
import java.util.List;

/**
 * 수업 목록 항목 (API 명세 8.2절). 문제 정보는 채우지 않고({@code currentProblem} null, {@code linkedProblems} 빈 배열),
 * 문제·참여·정답 집계를 더한다.
 */
public record LectureListItemResponse(
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
    List<LinkedProblemResponse> linkedProblems,
    int problemCount,
    int joinedCount,
    int participantCount,
    Integer correctRate) {

  public static LectureListItemResponse from(LectureListItem item) {
    Lecture lecture = item.lecture();
    return new LectureListItemResponse(
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
        List.of(),
        item.problemCount(),
        item.joinedCount(),
        item.participantCount(),
        item.correctRate());
  }
}
