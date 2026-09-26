package com.itda.lecture.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 수업 API의 보호 여부를 실제 HTTP로 확인한다. 경로가 SecurityConfig에서 빠지면 401이 아니라 500이 된다 ({@link
 * com.itda.learning.controller.LearningSessionApiIntegrationTest} 주석 참고).
 */
@WebApiTest
class LectureApiIntegrationTest {

  private static final String JSON = "application/json";

  @LocalServerPort private int port;

  private HttpTestClient client;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
  }

  private int status(String method, String path, String body) {
    HttpResponse<String> response =
        this.client.send(method, "/api/v1" + path, body, "Content-Type", JSON);
    return response.statusCode();
  }

  @Test
  void 토큰_없이_수업을_만들면_401이다() {
    assertThat(status("POST", "/lectures", "{\"title\":\"t\"}")).isEqualTo(401);
  }

  @Test
  void 토큰_없이_수업_목록을_보면_401이다() {
    assertThat(status("GET", "/lectures", null)).isEqualTo(401);
  }

  @Test
  void 토큰_없이_수업을_조회하면_401이다() {
    assertThat(status("GET", "/lectures/1", null)).isEqualTo(401);
  }

  @Test
  void 토큰_없이_상태를_바꾸면_401이다() {
    assertThat(status("PUT", "/lectures/1/status", "{\"status\":\"OPEN\"}")).isEqualTo(401);
  }

  @Test
  void 토큰_없이_코드로_입장하면_401이다() {
    assertThat(status("POST", "/lecture-participations", "{\"participationCode\":\"012345\"}"))
        .isEqualTo(401);
  }

  @Test
  void 토큰_없이_음성_토큰을_받으면_401이다() {
    assertThat(status("POST", "/lectures/1/media-tokens", null)).isEqualTo(401);
  }

  @Test
  void 토큰_없이_발언_권한을_바꾸면_401이다() {
    assertThat(status("PUT", "/lectures/1/participants/2/speaking", "{\"allowed\":true}"))
        .isEqualTo(401);
  }
}
