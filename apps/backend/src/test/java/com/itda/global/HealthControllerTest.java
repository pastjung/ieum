package com.itda.global;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

@WebApiTest
class HealthControllerTest {

  @LocalServerPort int port;

  @Test
  void health는_wrapper_없이_상태_DTO를_그대로_반환한다() {
    HttpResponse<String> response = new HttpTestClient(port).get("/api/v1/health");

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body).containsOnlyKeys("status", "service", "time");
    assertThat(body).containsEntry("status", "UP").containsEntry("service", "itda-backend");
  }

  @Test
  void health의_시각은_ISO_8601_UTC다() {
    HttpResponse<String> response = new HttpTestClient(port).get("/api/v1/health");

    String time = (String) HttpTestClient.json(response).get("time");
    assertThat(time).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
    assertThat(Instant.parse(time)).isBeforeOrEqualTo(Instant.now());
  }
}
