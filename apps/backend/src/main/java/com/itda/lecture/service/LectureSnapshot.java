package com.itda.lecture.service;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.problem.service.LinkedProblem;
import java.util.List;

/**
 * 요청자 관점의 수업 조회 결과. 강사와 학습자는 응답 필드가 다르다 (API 명세 8.2절).
 *
 * @param participation 학습자 보기에서만 값이 있다
 * @param currentProblem 현재 문제. 없으면 null
 * @param linkedProblems 강사 보기에서만 채운다(연결 순서)
 * @param myResult 학습자가 현재 문제에 제출했을 때만 값이 있다
 */
public record LectureSnapshot(
    Lecture lecture,
    Participant participation,
    LinkedProblem currentProblem,
    List<LinkedProblem> linkedProblems,
    LiveAnswer myResult) {

  /** 문제 정보를 채우지 않은 강사 보기 — 생성 직후·목록 항목처럼 연결 문제가 필요 없을 때. */
  public static LectureSnapshot ofInstructor(Lecture lecture) {
    return new LectureSnapshot(lecture, null, null, List.of(), null);
  }

  public static LectureSnapshot ofInstructor(
      Lecture lecture, LinkedProblem currentProblem, List<LinkedProblem> linkedProblems) {
    return new LectureSnapshot(lecture, null, currentProblem, List.copyOf(linkedProblems), null);
  }

  public static LectureSnapshot ofLearner(Lecture lecture, Participant participation) {
    return new LectureSnapshot(lecture, participation, null, List.of(), null);
  }

  public static LectureSnapshot ofLearner(
      Lecture lecture,
      Participant participation,
      LinkedProblem currentProblem,
      LiveAnswer myResult) {
    return new LectureSnapshot(lecture, participation, currentProblem, List.of(), myResult);
  }

  public boolean isInstructorView() {
    return participation == null;
  }

  /** 방 metadata에 쓰는 현재 문제 id (API 명세 8.5.2절). 없으면 null. */
  public Long currentProblemId() {
    return currentProblem == null ? null : currentProblem.problemId();
  }
}
