package com.itda.lecture.dto;

import com.itda.lecture.service.LearnerAnswer;
import com.itda.lecture.service.LectureAnswers;
import java.util.List;

/** 강사 답안 목록 (API 명세 8.2.2절). ID는 10진수 문자열이다. */
public record LectureAnswersResponse(String problemId, List<Item> answers) {

  public record Item(
      String userId,
      String username,
      boolean isCorrect,
      String attemptedAt,
      List<AnswerCellResponse> cells) {

    static Item from(LearnerAnswer answer) {
      return new Item(
          String.valueOf(answer.userId()),
          answer.username(),
          answer.answer().correct(),
          UtcTimes.format(answer.answer().attemptedAt()),
          answer.answer().cells().stream().map(AnswerCellResponse::from).toList());
    }
  }

  public static LectureAnswersResponse from(LectureAnswers answers) {
    return new LectureAnswersResponse(
        String.valueOf(answers.problemId()), answers.answers().stream().map(Item::from).toList());
  }
}
