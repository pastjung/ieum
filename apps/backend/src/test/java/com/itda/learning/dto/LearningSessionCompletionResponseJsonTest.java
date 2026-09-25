package com.itda.learning.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.learning.service.LearningSessionAnalysisService.SessionSummaryResult;
import com.itda.learning.service.LearningSessionCompletionService.CellResult;
import com.itda.learning.service.LearningSessionCompletionService.CompletionResult;
import com.itda.learning.service.LearningSessionCompletionService.ProblemResult;
import com.itda.support.web.WebApiTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 완료 응답의 {@code completedAt}이 JSON 문자열로 나가는지 확인한다.
 *
 * <p>앱의 응답 스키마는 {@code completedAt}을 문자열로 받는다(zod {@code z.string()}). 숫자(epoch)로 직렬화되면 파싱이 실패하는데,
 * 그 시점에는 <b>서버가 이미 저장을 끝낸 뒤</b>다 — 재시도해도 {@code handleCompletedSession}이 같은 응답을 다시 주므로 앱은 무한히 실패한다.
 * 사용자는 12문항을 다 풀고도 결과를 영영 볼 수 없다.
 *
 * <p>직렬화 설정은 {@code ObjectMapper}가 결정하므로 {@code new ObjectMapper()}로 확인하면 앱과 다른 것을 검증하게 된다. 그래서
 * 애플리케이션 컨텍스트의 bean을 그대로 주입받는다 — Spring MVC의 JSON 메시지 컨버터가 쓰는 것과 같은 인스턴스다.
 */
@WebApiTest
class LearningSessionCompletionResponseJsonTest {

  @Autowired private ObjectMapper objectMapper;

  private static final Instant COMPLETED_AT = Instant.parse("2026-09-24T01:23:45Z");

  @Test
  void completedAt이_JSON_문자열로_직렬화된다() {
    LearningSessionCompletionResponse response = sampleResponse();

    JsonNode root = this.objectMapper.readTree(this.objectMapper.writeValueAsString(response));

    assertThat(root.get("completedAt").isString()).isTrue();
    assertThat(Instant.parse(root.get("completedAt").asString())).isEqualTo(COMPLETED_AT);
  }

  @Test
  void 앱이_읽는_나머지_필드도_기대한_JSON_타입이다() {
    JsonNode root =
        this.objectMapper.readTree(this.objectMapper.writeValueAsString(sampleResponse()));

    assertThat(root.get("sessionId").isNumber()).isTrue();
    assertThat(root.get("totalProblemCount").intValue()).isEqualTo(1);
    assertThat(root.get("correctProblemCount").intValue()).isZero();

    JsonNode cell = root.get("problemResults").get(0).get("cells").get(0);
    assertThat(cell.get("expectedPattern").intValue()).isEqualTo(0b100000);
    assertThat(cell.get("missingDots").isArray()).isTrue();
  }

  private static LearningSessionCompletionResponse sampleResponse() {
    CellResult cell =
        new CellResult(1, 0b100000, 0b000001, 0b100001, 0b100000, 0b000001, List.of(6), List.of(1));
    ProblemResult problem = new ProblemResult(101L, 7L, 301L, false, List.of(cell));
    return LearningSessionCompletionResponse.from(
        new CompletionResult(
            12L, COMPLETED_AT, 1, 0, List.of(problem), SessionSummaryResult.notAvailable()));
  }
}
