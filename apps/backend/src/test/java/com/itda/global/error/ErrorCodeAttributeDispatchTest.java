package com.itda.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;

/**
 * 필터가 남긴 ErrorCode attribute가 ERROR dispatch에서 어떻게 쓰이는지 고정한다.
 *
 * <p>핵심은 attribute가 <b>상태를 바꾸지 못한다</b>는 것이다. attribute는 같은 상태 안에서 code를 더 정확하게 고르는 수단일 뿐이며, 어긋난
 * attribute를 따르면 "오류 상태는 그대로 유지한다"는 공통 계약이 깨진다.
 */
@WebApiTest
class ErrorCodeAttributeDispatchTest {

  @LocalServerPort private int port;

  private HttpTestClient client;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
  }

  private HttpResponse<String> sendErrorWithCode(int status, String code) {
    return this.client.get("/api/v1/test/send-error/" + status + "/with-code/" + code);
  }

  @Test
  void 상태와_code가_맞으면_attribute_code를_쓴다() {
    HttpResponse<String> response = sendErrorWithCode(503, "AUTH_STATE_UNAVAILABLE");

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(HttpTestClient.json(response)).containsEntry("code", "AUTH_STATE_UNAVAILABLE");
  }

  @Test
  void 상태가_다른_attribute는_무시하고_상태별_매핑을_쓴다() {
    HttpResponse<String> response = sendErrorWithCode(401, "AUTH_STATE_UNAVAILABLE");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(HttpTestClient.json(response)).containsEntry("code", "UNAUTHORIZED");
  }

  @Test
  void 상태가_다른_attribute를_무시한_뒤_매핑이_없으면_body를_비운다() {
    HttpResponse<String> response = sendErrorWithCode(503, "UNAUTHORIZED");

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).isEmpty();
  }

  @Test
  void attribute가_원래_HTTP_상태를_바꾸지_않는다() {
    assertThat(sendErrorWithCode(401, "AUTH_STATE_UNAVAILABLE").statusCode()).isEqualTo(401);
    assertThat(sendErrorWithCode(503, "UNAUTHORIZED").statusCode()).isEqualTo(503);
    assertThat(sendErrorWithCode(404, "AUTH_STATE_UNAVAILABLE").statusCode()).isEqualTo(404);
    assertThat(sendErrorWithCode(500, "UNAUTHORIZED").statusCode()).isEqualTo(500);
  }

  @Test
  void 상태가_다른_attribute를_무시해도_기존_code_매핑은_그대로다() {
    assertThat(HttpTestClient.json(sendErrorWithCode(404, "AUTH_STATE_UNAVAILABLE")))
        .containsEntry("code", "ENDPOINT_NOT_FOUND");
    assertThat(HttpTestClient.json(sendErrorWithCode(500, "UNAUTHORIZED")))
        .containsEntry("code", "INTERNAL_SERVER_ERROR");
  }

  @Test
  void attribute를_써도_X_Request_Id는_하나다() {
    HttpResponse<String> response = sendErrorWithCode(503, "AUTH_STATE_UNAVAILABLE");

    assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
  }

  @Test
  void JSON을_받지_않으면_attribute_code여도_body만_생략한다() {
    HttpResponse<String> response =
        this.client.get(
            "/api/v1/test/send-error/503/with-code/AUTH_STATE_UNAVAILABLE",
            HttpHeaders.ACCEPT,
            "text/html");

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
  }
}
