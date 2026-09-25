package com.itda.learning.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 학습 세션 API의 HTTP 계약을 실제 Tomcat으로 검증한다.
 *
 * <p>네 경로 모두 본인의 세션을 다루므로 Access Token 없이는 들어올 수 없어야 한다. 토큰이 없을 때 {@code 500}이 나간다면 인증 필터가 붙지 않아
 * {@code @AuthenticationPrincipal}이 null인 채로 서비스까지 들어갔다는 뜻이다 — 계약 위반이자 정보 노출이다.
 *
 * <p><b>보호 경로가 늘어나면 이 클래스에 테스트를 같이 더한다.</b> {@code attempt-analysis}가 {@link
 * com.itda.global.security.SecurityConfig}의 {@code OrRequestMatcher}에서 빠져 있었는데도 리뷰 8회를 통과한 이유가 여기에
 * 그 테스트가 없어서였다. 보호 여부는 코드를 읽어서가 아니라 실제 HTTP 응답으로만 확인된다.
 */
@WebApiTest
class LearningSessionApiIntegrationTest {

  private static final String SESSIONS = "/api/v1/learning-sessions";

  @LocalServerPort private int port;

  private HttpTestClient client;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
  }

  @Test
  void 토큰_없이_세션을_만들려_하면_401이다() {
    HttpResponse<String> response =
        this.client.send(
            "POST", SESSIONS, "{\"curriculumId\":1}", "Content-Type", "application/json");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void 토큰_없이_세션을_조회하면_401이다() {
    HttpResponse<String> response = this.client.get(SESSIONS + "/1");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void 토큰_없이_완료를_제출하면_401이다() {
    HttpResponse<String> response =
        this.client.send(
            "POST",
            SESSIONS + "/1/completion",
            "{\"attempts\":[]}",
            "Content-Type",
            "application/json");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void 토큰_없이_문항_분석을_요청하면_401이다() {
    // 이 경로가 보호 체인에서 빠져 있으면 permitAll chain으로 떨어져 토큰이 파싱되지 않고,
    // @AuthenticationPrincipal이 null인 채로 서비스에 들어가 500이 된다. 그러면 유효한 토큰을
    // 들고도 매 문항 제출이 실패하고 ProblemSession.completed가 찍히지 않아 세션을 끝낼 수 없다.
    HttpResponse<String> response =
        this.client.send(
            "POST",
            SESSIONS + "/1/attempt-analysis",
            "{\"problemSessionId\":1,\"inputPatterns\":[1]}",
            "Content-Type",
            "application/json");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void 토큰_없이_복습_세션을_만들려_하면_401이다() {
    // 이 경로가 보호 체인에서 빠져 있어 permitAll chain으로 떨어졌다. 그러면 토큰이 파싱되지
    // 않아 @AuthenticationPrincipal이 null이고, reviewWithNewSession이 user.userId()에서
    // 터져 401이 아니라 500이 된다 — 유효한 토큰을 들고도 복습을 시작할 수 없다
    HttpResponse<String> response =
        this.client.send("POST", "/api/v1/review-sessions", "", "Content-Type", "application/json");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void 토큰_없이_복습_세션을_조회하면_401이다() {
    HttpResponse<String> response =
        this.client.send(
            "GET", "/api/v1/review-sessions/1", null, "Content-Type", "application/json");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void 토큰_없이_진행_중인_세션을_조회하면_401이다() {
    HttpResponse<String> response =
        this.client.send(
            "GET", SESSIONS + "/in-progress", null, "Content-Type", "application/json");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void 토큰_없이_숙련_상태를_조회하면_401이다() {
    HttpResponse<String> response =
        this.client.send(
            "GET", SESSIONS + "/skill-summary", null, "Content-Type", "application/json");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void 완료_경로가_context_path를_두_번_붙이지_않는다() {
    // context-path(/api/v1)와 클래스 @RequestMapping이 겹쳐 /api/v1/api/v1/... 에 붙어 있었다.
    // 그 경로는 존재하지 않아야 한다 — 남아 있으면 문서와 다른 경로가 계속 살아 있다는 뜻이다.
    HttpResponse<String> response =
        this.client.send(
            "POST",
            "/api/v1/api/v1/learning-sessions/1/completion",
            "{\"attempts\":[]}",
            "Content-Type",
            "application/json");

    assertThat(response.statusCode()).isEqualTo(404);
  }
}
