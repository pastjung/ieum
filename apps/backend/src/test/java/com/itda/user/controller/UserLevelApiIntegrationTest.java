package com.itda.user.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 학습 단계 설정 API의 HTTP 계약을 실제 Tomcat으로 검증한다.
 *
 * <p>내 정보를 바꾸는 경로라 Access Token 없이는 들어올 수 없어야 한다. 토큰이 없을 때 {@code 401}이 아닌 것이 나간다면 {@link
 * com.itda.global.security.SecurityConfig}의 {@code OrRequestMatcher}에서 빠졌다는 뜻이다 — 요청이 permitAll
 * chain으로 떨어져 인증 필터를 거치지 않고 {@code @AuthenticationPrincipal}이 null인 채로 서비스까지 들어간다.
 *
 * <p>이 브랜치에서만 그 누락이 두 번 있었다({@code attempt-analysis}, {@code review-sessions}). 보호 여부는 코드를 읽어서가
 * 아니라 실제 HTTP 응답으로만 확인된다.
 */
@WebApiTest
class UserLevelApiIntegrationTest {

  private static final String LEVEL_PATH = "/api/v1/users/me/level";

  @LocalServerPort private int port;

  private HttpTestClient client;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
  }

  @Test
  void 토큰_없이_학습_단계를_바꾸려_하면_401이다() {
    HttpResponse<String> response =
        this.client.send(
            "PUT", LEVEL_PATH, "{\"level\":\"WORD\"}", "Content-Type", "application/json");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void 경로가_context_path를_두_번_붙이지_않는다() {
    HttpResponse<String> response =
        this.client.send(
            "PUT",
            "/api/v1/api/v1/users/me/level",
            "{\"level\":\"WORD\"}",
            "Content-Type",
            "application/json");

    assertThat(response.statusCode()).isEqualTo(404);
  }
}
