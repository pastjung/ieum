package com.itda.lecture.dto;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.service.LectureSnapshot;
import java.util.List;

/** 참여 학습자용 Snapshot (API 명세 8.2절). 참여 코드·다른 참여자 정보·제출 전 정답은 넣지 않는다. */
public record LearnerLectureResponse(
    String lectureId,
    String title,
    String description,
    LectureStatus status,
    String scheduledAt,
    String startedAt,
    String endedAt,
    LearnerCurrentProblemResponse currentProblem,
    String createdAt,
    MyParticipation myParticipation,
    LiveAnswerResponse myResult,
    List<Object> reviewableCurriculumStages) {

  public record MyParticipation(String joinedAt, String leftAt) {}

  public static LearnerLectureResponse from(LectureSnapshot snapshot) {
    Lecture lecture = snapshot.lecture();
    Participant participation = snapshot.participation();
    boolean submitted = snapshot.myResult() != null;
    return new LearnerLectureResponse(
        String.valueOf(lecture.getId()),
        lecture.getTitle(),
        lecture.getDescription(),
        lecture.getStatus(),
        UtcTimes.format(lecture.getScheduledAt()),
        UtcTimes.format(lecture.getStartedAt()),
        UtcTimes.format(lecture.getEndedAt()),
        snapshot.currentProblem() == null
            ? null
            : LearnerCurrentProblemResponse.from(snapshot.currentProblem(), submitted),
        UtcTimes.format(lecture.getCreatedAt()),
        new MyParticipation(
            UtcTimes.format(participation.getJoinedAt()),
            UtcTimes.format(participation.getLeftAt())),
        submitted ? LiveAnswerResponse.from(snapshot.myResult()) : null,
        List.of());
  }
}
