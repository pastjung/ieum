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
 * <p>세 경로 모두 본인의 세션을 다루므로 Access Token 없이는 들어올 수 없어야 한다. 토큰이 없을 때 {@code 500}이 나간다면 인증 필터가 붙지 않아
 * {@code @AuthenticationPrincipal}이 null인 채로 서비스까지 들어갔다는 뜻이다 — 계약 위반이자 정보 노출이다.
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
