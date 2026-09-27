package com.itda.learning.service;

import com.itda.problem.domain.ErrorType;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 정답 셀과 입력 셀을 견주어 오류 유형을 센다.
 *
 * <p>FastAPI 채점기를 거치지 않는 낱자 문항(자음·모음 단계)에서 쓴다. AI는 {@code target_text}를 자모로 분해해 규칙을 적용하므로 'ㄱ'·'ㅙ'
 * 같은 낱자를 받지 못한다 — 그 경로에서도 학습자에게 "무엇을 틀렸는지"는 말해 줘야 하고, 세션 결과 화면의 오류 유형 집계도 비면 안 된다. 비어 있으면 화면이 "눈에
 * 띄는 실수가 없었어요"라고 잘못 말한다.
 *
 * <p>여기서 만드는 키는 {@link ErrorType}의 이름을 소문자로 적은 것이다. {@code
 * LearningSessionAnalysisValidator#parseErrorType}이 그 형식을 그대로 요구하므로, 다른 형식을 쓰면 저장 단계에서 풀이 전체가 거부된다.
 */
final class AttemptErrorClassifier {

  private static final int MAX_PATTERN = 0b111111;

  private AttemptErrorClassifier() {}

  /**
   * 풀이 하나의 오류 유형별 횟수. 맞은 칸은 세지 않으므로 정답이면 빈 맵이다.
   *
   * <p>칸 수가 다르면 남거나 모자란 칸을 {@code cell_inserted}·{@code cell_omitted}로 센다. 점 차이는 칸마다 하나의 유형으로만 센다 —
   * 빠진 점과 더 눌린 점이 함께 있으면 {@code dot_mixed}다.
   */
  static Map<String, Long> classify(List<Integer> expectedPatterns, List<Integer> inputPatterns) {
    Map<String, Long> counts = new HashMap<>();
    int cellCount = Math.max(expectedPatterns.size(), inputPatterns.size());
    for (int index = 0; index < cellCount; index++) {
      if (index >= expectedPatterns.size()) {
        increment(counts, ErrorType.CELL_INSERTED);
        continue;
      }
      if (index >= inputPatterns.size()) {
        increment(counts, ErrorType.CELL_OMITTED);
        continue;
      }
      int expected = expectedPatterns.get(index);
      int input = inputPatterns.get(index);
      int missing = expected & ~input & MAX_PATTERN;
      int extra = input & ~expected & MAX_PATTERN;
      if (missing != 0 && extra != 0) {
        increment(counts, ErrorType.DOT_MIXED);
      } else if (missing != 0) {
        increment(counts, ErrorType.DOT_MISSING);
      } else if (extra != 0) {
        increment(counts, ErrorType.DOT_EXTRA);
      }
    }
    return Map.copyOf(counts);
  }

  /** 세션 요약용 — 여러 풀이의 집계를 하나로 합친다 */
  static Map<String, Long> merge(List<Map<String, Long>> counts) {
    Map<String, Long> merged = new HashMap<>();
    for (Map<String, Long> one : counts) {
      one.forEach((type, count) -> merged.merge(type, count, Long::sum));
    }
    return Map.copyOf(merged);
  }

  private static void increment(Map<String, Long> counts, ErrorType type) {
    counts.merge(type.name().toLowerCase(Locale.ROOT), 1L, Long::sum);
  }
}
