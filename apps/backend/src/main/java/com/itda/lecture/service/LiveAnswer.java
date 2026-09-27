package com.itda.lecture.service;

import com.itda.learning.domain.AttemptCell;
import com.itda.learning.domain.ProblemAttempt;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** 채점·저장된 LIVE 답안 (API 명세 8.2.2절 LiveAnswer). attemptedAt은 UTC다. */
public record LiveAnswer(
    long attemptId,
    long lectureId,
    long problemId,
    boolean correct,
    int score,
    LocalDateTime attemptedAt,
    List<AnswerCell> cells) {

  /** 저장된 풀이에서 다시 만든다. 점수는 저장하지 않으므로 칸에서 다시 계산한다 — 제출 때와 같은 규칙이다. */
  static LiveAnswer of(ProblemAttempt attempt, List<AttemptCell> storedCells) {
    List<AnswerCell> cells =
        storedCells.stream()
            .sorted(Comparator.comparingInt(AttemptCell::getCellIndex))
            .map(AnswerCell::from)
            .toList();
    return new LiveAnswer(
        attempt.getId(),
        attempt.getLectureId(),
        attempt.getProblemId(),
        attempt.isCorrect(),
        LiveAnswerGrader.score(attempt.isCorrect(), cells),
        attempt.getAttemptedAt(),
        cells);
  }
}
