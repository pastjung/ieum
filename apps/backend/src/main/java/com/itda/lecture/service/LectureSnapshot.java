package com.itda.lecture.service;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;

/**
 * 요청자 관점의 수업 조회 결과. 강사와 학습자는 응답 필드가 다르다 (API 명세 8.2절).
 *
 * @param participation 학습자 보기에서만 값이 있다
 */
public record LectureSnapshot(Lecture lecture, Participant participation) {

  public static LectureSnapshot ofInstructor(Lecture lecture) {
    return new LectureSnapshot(lecture, null);
  }

  public static LectureSnapshot ofLearner(Lecture lecture, Participant participation) {
    return new LectureSnapshot(lecture, participation);
  }

  public boolean isInstructorView() {
    return participation == null;
  }
}
