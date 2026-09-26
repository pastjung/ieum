package com.itda.learning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttemptErrorClassifierTest {

  @Test
  void 정답이면_오류가_없다() {
    assertThat(AttemptErrorClassifier.classify(List.of(0b000101), List.of(0b000101))).isEmpty();
  }

  @Test
  void 점이_빠지면_dot_missing() {
    // 정답 1·3번인데 1번만 눌렀다
    assertThat(AttemptErrorClassifier.classify(List.of(0b000101), List.of(0b000001)))
        .isEqualTo(Map.of("dot_missing", 1L));
  }

  @Test
  void 점을_더_누르면_dot_extra() {
    assertThat(AttemptErrorClassifier.classify(List.of(0b000001), List.of(0b000101)))
        .isEqualTo(Map.of("dot_extra", 1L));
  }

  @Test
  void 빠진_점과_더_눌린_점이_함께_있으면_dot_mixed() {
    // 정답 1·3번인데 1·2번을 눌렀다 — 가장 흔한 오답 형태
    assertThat(AttemptErrorClassifier.classify(List.of(0b000101), List.of(0b000011)))
        .isEqualTo(Map.of("dot_mixed", 1L));
  }

  @Test
  void 칸을_덜_입력하면_cell_omitted() {
    assertThat(AttemptErrorClassifier.classify(List.of(0b000101, 0b000010), List.of(0b000101)))
        .isEqualTo(Map.of("cell_omitted", 1L));
  }

  @Test
  void 칸을_더_입력하면_cell_inserted() {
    assertThat(AttemptErrorClassifier.classify(List.of(0b000101), List.of(0b000101, 0b000010)))
        .isEqualTo(Map.of("cell_inserted", 1L));
  }

  @Test
  void 여러_칸의_오류를_유형별로_센다() {
    Map<String, Long> counts =
        AttemptErrorClassifier.classify(
            List.of(0b000101, 0b000101, 0b000001), List.of(0b000001, 0b000001, 0b000101));

    assertThat(counts).isEqualTo(Map.of("dot_missing", 2L, "dot_extra", 1L));
  }

  @Test
  void 맞은_칸은_세지_않는다() {
    Map<String, Long> counts =
        AttemptErrorClassifier.classify(List.of(0b000101, 0b000010), List.of(0b000101, 0b000110));

    assertThat(counts).isEqualTo(Map.of("dot_extra", 1L));
  }

  @Test
  void 빈_칸을_입력해도_빠진_점으로_센다() {
    // 앱이 아무 점도 짚지 않고 확정한 칸은 0으로 온다
    assertThat(AttemptErrorClassifier.classify(List.of(0b000101), List.of(0)))
        .isEqualTo(Map.of("dot_missing", 1L));
  }

  @Test
  void 정답이_빈_칸인데_점을_누르면_더_눌린_것으로_센다() {
    assertThat(AttemptErrorClassifier.classify(List.of(0), List.of(0b000010)))
        .isEqualTo(Map.of("dot_extra", 1L));
  }

  @Test
  void 만들어_낸_키는_모두_ErrorType_으로_읽을_수_있다() {
    // LearningSessionAnalysisValidator.parseErrorType 이 소문자 이름을 그대로 요구한다 —
    // 여기서 만든 키가 저장 단계에서 거부되면 낱자 문항 풀이가 통째로 실패한다
    List<Map<String, Long>> all =
        List.of(
            AttemptErrorClassifier.classify(List.of(0b000101), List.of(0b000001)),
            AttemptErrorClassifier.classify(List.of(0b000001), List.of(0b000101)),
            AttemptErrorClassifier.classify(List.of(0b000101), List.of(0b000011)),
            AttemptErrorClassifier.classify(List.of(0b000101, 0b000010), List.of(0b000101)),
            AttemptErrorClassifier.classify(List.of(0b000101), List.of(0b000101, 0b000010)));

    assertThat(all)
        .allSatisfy(
            counts ->
                counts
                    .keySet()
                    .forEach(key -> LearningSessionAnalysisValidator.parseErrorType(key)));
  }

  @Test
  void 여러_풀이의_오류를_합친다() {
    Map<String, Long> first = AttemptErrorClassifier.classify(List.of(0b000101), List.of(0b000001));
    Map<String, Long> second =
        AttemptErrorClassifier.classify(List.of(0b000101), List.of(0b000001));

    assertThat(AttemptErrorClassifier.merge(List.of(first, second)))
        .isEqualTo(Map.of("dot_missing", 2L));
  }
}
