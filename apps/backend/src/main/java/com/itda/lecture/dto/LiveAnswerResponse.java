package com.itda.lecture.dto;

import com.itda.lecture.service.LiveAnswer;
import java.util.List;

/** API 명세 8.2.2절 LiveAnswer. 제출 응답과 학습자 Snapshot의 myResult가 같이 쓴다. */
public record LiveAnswerResponse(
    String attemptId,
    String lectureId,
    String problemId,
    boolean isCorrect,
    int score,
    String attemptedAt,
    List<AnswerCellResponse> cells) {

  public static LiveAnswerResponse from(LiveAnswer answer) {
    return new LiveAnswerResponse(
        String.valueOf(answer.attemptId()),
        String.valueOf(answer.lectureId()),
        String.valueOf(answer.problemId()),
        answer.correct(),
        answer.score(),
        UtcTimes.format(answer.attemptedAt()),
        answer.cells().stream().map(AnswerCellResponse::from).toList());
  }
}
