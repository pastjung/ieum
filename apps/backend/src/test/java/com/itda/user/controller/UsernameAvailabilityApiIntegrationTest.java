package com.itda.user.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.service.CompromisedPasswordChecker;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 아이디 사용 가능 확인 API의 HTTP 계약을 실제 Tomcat으로 검증한다 (API 명세 3.2.1절).
 *
 * <p>인증을 요구하지 않는 조회다. 가입 화면이 로그인 전에 부르므로 Access Token 없이 {@code 200}이 나와야 한다.
 */
@WebApiTest
class UsernameAvailabilityApiIntegrationTest {

  private static final String AVAILABILITY = "/api/v1/username-availability";
  private static final String USERS = "/api/v1/users";
  private static final String VALID_PASSWORD = "충분히 긴 새 비밀번호 문구";

  private static final AtomicLong SEQUENCE = new AtomicLong();

  @LocalServerPort private int port;

  /** 가입으로 "이미 쓰는 아이디"를 만들 때만 필요하다. 외부 HIBP를 자동 테스트에서 부르지 않는다. */
  @MockitoBean private CompromisedPasswordChecker compromisedPasswordChecker;

  private HttpTestClient client;
  private String username;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
    this.username = "learner" + SEQUENCE.incrementAndGet();
    org.mockito.Mockito.when(
            this.compromisedPasswordChecker.check(org.mockito.ArgumentMatchers.any()))
        .thenReturn(CompromisedPasswordChecker.CheckResult.NOT_COMPROMISED);
  }

  @Test
  void 쓰이지_않는_아이디는_사용_가능으로_답한다() {
    HttpResponse<String> response = this.client.get(AVAILABILITY + "?username=" + this.username);

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body.get("username")).isEqualTo(this.username);
    assertThat(body.get("available")).isEqualTo(true);
  }

  @Test
  void 이미_쓰는_아이디는_사용_불가로_답한다() {
    register(this.username);

    HttpResponse<String> response = this.client.get(AVAILABILITY + "?username=" + this.username);

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body.get("available")).isEqualTo(false);
  }

  // 대소문자만 다른 아이디의 판정은 여기서 검증하지 않는다. 계약(3.2.1절)은 "대소문자 비구분
  // UNIQUE"지만 그 성질은 DB collation이 만들고, 이 테스트는 H2(대소문자 구분)로 돌기 때문이다.
  // User 엔티티도 같은 이유를 적어 두었다 — "이 성질은 실제 MariaDB에서만 성립한다, collation
  // 고정은 Flyway 이슈로 넘긴다". 이 조회는 가입과 같은 existsByUsername을 쓰므로 어느 DB에서든
  // 가입과 같은 답을 낸다. 둘이 어긋나는 것이 위험이지, 대소문자 자체가 위험이 아니다.
  // 검증이 필요해지면 LoginMariadbTest처럼 실제 MariaDB에 대고 보는 자리가 맞다.

  @Test
  void 정규화한_아이디를_돌려준다() {
    HttpResponse<String> response =
        this.client.get(AVAILABILITY + "?username=%20%20" + this.username + "%20%20");

    assertThat(response.statusCode()).isEqualTo(200);
    // 클라이언트는 무엇에 대한 판정인지 알아야 한다. 요청 원문을 그대로 되돌려 주면 안 된다
    assertThat(HttpTestClient.json(response).get("username")).isEqualTo(this.username);
  }

  @Test
  void 형식을_위반하면_사용_불가가_아니라_422다() {
    // 3.2.1절: 3자 미만은 형식 위반이다. available=false로 답하면 중복인지 형식인지 구분되지 않아
    // 클라이언트가 사용자에게 무엇을 고치라고 안내할 수 없다
    HttpResponse<String> response = this.client.get(AVAILABILITY + "?username=ab");

    assertThat(response.statusCode()).isEqualTo(422);
    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body.get("code")).isEqualTo("INVALID_ACCOUNT_INPUT");
    assertThat(body).doesNotContainKey("available");
  }

  @Test
  void 허용하지_않는_문자는_422다() {
    HttpResponse<String> response = this.client.get(AVAILABILITY + "?username=hello%40world");

    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(HttpTestClient.json(response).get("code")).isEqualTo("INVALID_ACCOUNT_INPUT");
  }

  @Test
  void username_파라미터가_없으면_422다() {
    HttpResponse<String> response = this.client.get(AVAILABILITY);

    // 누락을 공통 400으로 흘리지 않는다. 이 API의 계약은 422 INVALID_ACCOUNT_INPUT이다
    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(HttpTestClient.json(response).get("code")).isEqualTo("INVALID_ACCOUNT_INPUT");
  }

  /** 아이디를 점유시키기 위한 가입. 이 API가 아니라 계정 생성 계약을 그대로 쓴다. */
  private void register(String name) {
    HttpResponse<String> response =
        this.client.send(
            "POST",
            USERS,
            """
            {"username":"%s","email":"%s@example.test","password":"%s"}"""
                .formatted(name, name, VALID_PASSWORD),
            "Content-Type",
            "application/json");
    assertThat(response.statusCode()).isEqualTo(201);
  }
}
