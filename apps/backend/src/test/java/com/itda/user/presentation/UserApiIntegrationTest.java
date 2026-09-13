package com.itda.user.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.application.port.out.AccessTokenClaims;
import com.itda.user.application.port.out.AccessTokenIssuer;
import com.itda.user.application.port.out.CompromisedPasswordCheckResult;
import com.itda.user.application.port.out.CompromisedPasswordChecker;
import com.itda.user.domain.Email;
import com.itda.user.domain.MailOutboxEntry;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRepository;
import com.itda.user.domain.UserRole;
import com.itda.user.infrastructure.MailOutboxJpaRepository;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 계정 API의 HTTP 계약을 실제 Tomcat으로 검증한다 (API 명세 3.2·3.2.1절).
 *
 * <p>MockMvc를 쓰지 않는다. {@code /error} dispatch·{@code sendError}·Security chain 순서 같은 컨테이너 동작이 재현되지
 * 않아 401·404·405 계약을 실제와 같게 확인할 수 없다.
 *
 * <p>유출 조회만 stub으로 바꾼다. 외부 HIBP를 자동 테스트에서 실제로 부르지 않기 위해서이며, 나머지 경로(정규화·해시·트랜잭션·저장)는 운영과 같은 bean을
 * 쓴다.
 */
@WebApiTest
class UserApiIntegrationTest {

  private static final String USERS = "/api/v1/users";
  private static final String ME = "/api/v1/users/me";
  private static final String HEALTH = "/api/v1/health";
  private static final String UNKNOWN = "/api/v1/no-such-endpoint";
  private static final String VALID_PASSWORD = "충분히 긴 새 비밀번호 문구";

  private static final AtomicLong SEQUENCE = new AtomicLong();

  @LocalServerPort private int port;
  @Autowired private UserRepository userRepository;
  @Autowired private MailOutboxJpaRepository mailOutboxJpaRepository;
  @Autowired private AccessTokenIssuer accessTokenIssuer;

  /** 외부 유출 조회만 대체한다. 테스트마다 결과를 바꿔 422·503 경로를 만든다. */
  @MockitoBean private CompromisedPasswordChecker compromisedPasswordChecker;

  private HttpTestClient client;
  private String username;
  private String email;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
    long sequence = SEQUENCE.incrementAndGet();
    this.username = "learner" + sequence;
    this.email = "user" + sequence + "@example.test";
    org.mockito.Mockito.when(
            this.compromisedPasswordChecker.check(org.mockito.ArgumentMatchers.any()))
        .thenReturn(CompromisedPasswordCheckResult.NOT_COMPROMISED);
  }

  private HttpResponse<String> post(String body) {
    return this.client.send("POST", USERS, body, HttpHeaders.CONTENT_TYPE, "application/json");
  }

  private HttpResponse<String> register() {
    return post(json(this.username, this.email, VALID_PASSWORD));
  }

  private static String json(String username, String email, String password) {
    return """
        {"username": %s, "email": %s, "password": %s}
        """
        .formatted(quote(username), quote(email), quote(password));
  }

  private static String quote(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private static void assertErrorCode(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(HttpTestClient.json(response)).containsEntry("code", code);
    assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
  }

  // ---------- 회원가입 성공 ----------

  @Test
  void 가입하면_201과_사용자_요약을_돌려준다() {
    HttpResponse<String> response = register();

    assertThat(response.statusCode()).isEqualTo(201);
    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body)
        .containsOnlyKeys(
            "userId", "username", "email", "role", "emailVerified", "level", "createdAt");
    assertThat(body.get("userId")).isInstanceOf(String.class);
    assertThat(Long.parseLong((String) body.get("userId"))).isPositive();
    assertThat(body).containsEntry("username", this.username);
    assertThat(body).containsEntry("email", this.email);
    assertThat(body).containsEntry("role", "LEARNER");
    assertThat(body).containsEntry("emailVerified", false);
    assertThat(body).containsEntry("level", null);
    assertThat((String) body.get("createdAt")).endsWith("Z").contains("T");
    assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
  }

  @Test
  void level_필드는_생략하지_않고_null로_보낸다() {
    assertThat(register().body()).contains("\"level\"");
  }

  @Test
  void 성공_응답에_wrapper와_Location과_토큰이_없다() {
    HttpResponse<String> response = register();

    // 공통 success/data wrapper를 씌우지 않는다.
    assertThat(HttpTestClient.json(response)).doesNotContainKeys("success", "data");
    assertThat(response.headers().firstValue(HttpHeaders.LOCATION)).isEmpty();
    assertThat(response.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(response.body()).doesNotContain("accessToken").doesNotContain("refreshToken");
  }

  @Test
  void 응답과_저장에_비밀번호_원문이_없다() {
    HttpResponse<String> response = register();

    assertThat(response.body()).doesNotContain(VALID_PASSWORD);
    User saved = this.userRepository.findByUsername(this.username).orElseThrow();
    assertThat(saved.getPasswordHash()).isNotEqualTo(VALID_PASSWORD).startsWith("$argon2id$");
  }

  @Test
  void 가입과_함께_이메일_인증_메일_작업을_접수한다() {
    register();

    List<MailOutboxEntry> entries = this.mailOutboxJpaRepository.findAll();
    assertThat(entries)
        .filteredOn(entry -> this.email.equals(entry.getRecipientEmail()))
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.getPurpose().name()).isEqualTo("EMAIL_VERIFICATION");
              assertThat(entry.getAttemptCount()).isZero();
            });
  }

  @Test
  void 정규화한_값으로_저장하고_응답한다() {
    HttpResponse<String> response =
        post(
            json(
                "  " + this.username + "  ",
                "  " + this.email.toUpperCase() + " ",
                VALID_PASSWORD));

    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body).containsEntry("username", this.username);
    assertThat(body).containsEntry("email", this.email);
  }

  @Test
  void 서버_관리_필드를_보내도_생성_값이_바뀌지_않는다() {
    String body =
        """
        {"username": "%s", "email": "%s", "password": "%s",
         "role": "ADMIN", "status": "WITHDRAWN", "emailVerified": true,
         "level": "L6", "authVersion": 99, "rowVersion": 99, "id": 12345}
        """
            .formatted(this.username, this.email, VALID_PASSWORD);

    HttpResponse<String> response = post(body);

    assertThat(response.statusCode()).isEqualTo(201);
    Map<String, Object> summary = HttpTestClient.json(response);
    assertThat(summary).containsEntry("role", "LEARNER");
    assertThat(summary).containsEntry("emailVerified", false);
    assertThat(summary).containsEntry("level", null);
    assertThat(summary.get("userId")).isNotEqualTo("12345");
    User saved = this.userRepository.findByUsername(this.username).orElseThrow();
    assertThat(saved.getRole()).isEqualTo(UserRole.LEARNER);
    assertThat(saved.getAuthVersion()).isZero();
    assertThat(saved.getEmailVerifiedAt()).isNull();
  }

  // ---------- 회원가입 오류 ----------

  @Test
  void 잘못된_JSON은_400_MALFORMED_REQUEST_BODY다() {
    assertErrorCode(post("{\"username\": "), 400, "MALFORMED_REQUEST_BODY");
  }

  @Test
  void body가_없으면_400이다() {
    assertErrorCode(post(null), 400, "MALFORMED_REQUEST_BODY");
  }

  @Test
  void 필드_타입이_다르면_400이다() {
    assertErrorCode(
        post("{\"username\": 1, \"email\": \"a@b.com\", \"password\": \"x\"}"),
        400,
        "MALFORMED_REQUEST_BODY");
  }

  @Test
  void 필수_필드가_없으면_422_INVALID_ACCOUNT_INPUT이다() {
    assertErrorCode(
        post("{\"email\": \"a@b.com\", \"password\": \"%s\"}".formatted(VALID_PASSWORD)),
        422,
        "INVALID_ACCOUNT_INPUT");
    assertErrorCode(post(json(this.username, null, VALID_PASSWORD)), 422, "INVALID_ACCOUNT_INPUT");
    assertErrorCode(post(json(this.username, this.email, null)), 422, "INVALID_ACCOUNT_INPUT");
  }

  @Test
  void 아이디_형식_위반은_422_INVALID_ACCOUNT_INPUT이다() {
    assertErrorCode(post(json("ab", this.email, VALID_PASSWORD)), 422, "INVALID_ACCOUNT_INPUT");
    assertErrorCode(post(json("-abc", this.email, VALID_PASSWORD)), 422, "INVALID_ACCOUNT_INPUT");
  }

  @Test
  void 이메일_형식_위반은_422_INVALID_ACCOUNT_INPUT이다() {
    assertErrorCode(
        post(json(this.username, "not-an-email", VALID_PASSWORD)), 422, "INVALID_ACCOUNT_INPUT");
  }

  @Test
  void 비밀번호_정책_위반은_422_INVALID_PASSWORD다() {
    assertErrorCode(post(json(this.username, this.email, "short")), 422, "INVALID_PASSWORD");
  }

  @Test
  void 유출_조회_장애는_503_PASSWORD_POLICY_UNAVAILABLE이다() {
    org.mockito.Mockito.when(
            this.compromisedPasswordChecker.check(org.mockito.ArgumentMatchers.any()))
        .thenReturn(CompromisedPasswordCheckResult.UNAVAILABLE);

    assertErrorCode(register(), 503, "PASSWORD_POLICY_UNAVAILABLE");
  }

  @Test
  void 아이디_중복은_409_ACCOUNT_IDENTIFIER_UNAVAILABLE이다() {
    assertThat(register().statusCode()).isEqualTo(201);

    HttpResponse<String> duplicate =
        post(json(this.username, "other" + this.email, VALID_PASSWORD));

    assertErrorCode(duplicate, 409, "ACCOUNT_IDENTIFIER_UNAVAILABLE");
  }

  @Test
  void 이메일_중복도_같은_409다() {
    assertThat(register().statusCode()).isEqualTo(201);

    HttpResponse<String> duplicate = post(json(this.username + "x", this.email, VALID_PASSWORD));

    assertErrorCode(duplicate, 409, "ACCOUNT_IDENTIFIER_UNAVAILABLE");
    // 어느 값이 중복인지 알려 주지 않는다 (3.2.1절).
    assertThat(duplicate.body()).doesNotContain("email").doesNotContain("username");
  }

  @Test
  void 오류_응답에_입력값이_없다() {
    HttpResponse<String> response = post(json("-bad-", this.email, VALID_PASSWORD));

    assertThat(response.body())
        .doesNotContain("-bad-")
        .doesNotContain(this.email)
        .doesNotContain(VALID_PASSWORD);
  }

  // ---------- 내 정보 ----------

  private String verifiedUserToken() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    User user =
        User.create(this.username, "argon2id$hash", UserRole.LEARNER, Email.of(this.email), now);
    user.verifyEmail(now);
    long userId = this.userRepository.save(user).getId();
    return this.accessTokenIssuer
        .issue(new AccessTokenClaims(userId, UserRole.LEARNER, 0))
        .compactToken();
  }

  private long otherVerifiedUserId() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    User other =
        User.create(
            this.username + "-other",
            "argon2id$hash",
            UserRole.LEARNER,
            Email.of("other-" + this.email),
            now);
    other.verifyEmail(now);
    return this.userRepository.save(other).getId();
  }

  @Test
  void 유효한_Access_Token으로_내_정보를_돌려준다() {
    String token = verifiedUserToken();

    HttpResponse<String> response =
        this.client.get(ME, HttpHeaders.AUTHORIZATION, "Bearer " + token);

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body)
        .containsOnlyKeys(
            "userId", "username", "email", "role", "emailVerified", "level", "createdAt");
    assertThat(body).containsEntry("username", this.username);
    assertThat(body).containsEntry("email", this.email);
    assertThat(body).containsEntry("emailVerified", true);
    assertThat(body).containsEntry("level", null);
    assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
  }

  @Test
  void JWT가_아니라_현재_DB_값으로_응답한다() {
    String token = verifiedUserToken();
    User stored = this.userRepository.findByUsername(this.username).orElseThrow();
    stored.changeLevel("L4", LocalDateTime.now(ZoneOffset.UTC));
    this.userRepository.save(stored);

    Map<String, Object> body =
        HttpTestClient.json(this.client.get(ME, HttpHeaders.AUTHORIZATION, "Bearer " + token));

    // 토큰에는 level claim이 없다. DB를 읽었다는 증거다.
    assertThat(body).containsEntry("level", "L4");
  }

  @Test
  void 토큰이_없거나_형식이_틀리면_401이다() {
    assertErrorCode(this.client.get(ME), 401, "UNAUTHORIZED");
    assertErrorCode(
        this.client.get(ME, HttpHeaders.AUTHORIZATION, "Basic abc"), 401, "UNAUTHORIZED");
    assertErrorCode(
        this.client.get(ME, HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"), 401, "UNAUTHORIZED");
  }

  @Test
  void 이메일_미인증_계정의_토큰은_401이다() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    User user =
        User.create(this.username, "argon2id$hash", UserRole.LEARNER, Email.of(this.email), now);
    long userId = this.userRepository.save(user).getId();
    String token =
        this.accessTokenIssuer
            .issue(new AccessTokenClaims(userId, UserRole.LEARNER, 0))
            .compactToken();

    assertErrorCode(
        this.client.get(ME, HttpHeaders.AUTHORIZATION, "Bearer " + token), 401, "UNAUTHORIZED");
  }

  @Test
  void authVersion이_다르면_401이다() {
    verifiedUserToken();
    long userId = this.userRepository.findByUsername(this.username).orElseThrow().getId();
    String staleToken =
        this.accessTokenIssuer
            .issue(new AccessTokenClaims(userId, UserRole.LEARNER, 99))
            .compactToken();

    assertErrorCode(
        this.client.get(ME, HttpHeaders.AUTHORIZATION, "Bearer " + staleToken),
        401,
        "UNAUTHORIZED");
  }

  @Test
  void 식별자를_query나_헤더로_지정해도_무시한다() {
    String token = verifiedUserToken();
    long otherUserId = otherVerifiedUserId();

    Map<String, Object> body =
        HttpTestClient.json(
            this.client.get(
                ME + "?userId=" + otherUserId,
                HttpHeaders.AUTHORIZATION,
                "Bearer " + token,
                "X-User-Id",
                Long.toString(otherUserId)));

    // 요청이 고른 값이 아니라 principal이 근거다 (3.2.1절).
    assertThat(body).containsEntry("username", this.username);
  }

  @Test
  void 인증_요청_뒤에도_다음_요청은_인증되지_않는다() {
    assertThat(
            this.client
                .get(ME, HttpHeaders.AUTHORIZATION, "Bearer " + verifiedUserToken())
                .statusCode())
        .isEqualTo(200);

    // SecurityContext가 thread에 남으면 같은 Tomcat worker를 받은 이 요청이 200이 된다.
    assertErrorCode(this.client.get(ME), 401, "UNAUTHORIZED");
  }

  // ---------- 인증 범위 회귀 ----------

  @Test
  void 회원가입은_Authorization_헤더_없이도_열려_있다() {
    assertThat(register().statusCode()).isEqualTo(201);
  }

  @Test
  void 공개_경로는_잘못된_토큰의_영향을_받지_않는다() {
    assertThat(
            this.client
                .send(
                    "POST",
                    USERS,
                    json(this.username, this.email, VALID_PASSWORD),
                    HttpHeaders.CONTENT_TYPE,
                    "application/json",
                    HttpHeaders.AUTHORIZATION,
                    "Bearer not-a-jwt")
                .statusCode())
        .isEqualTo(201);
    assertThat(this.client.get(HEALTH, HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt").statusCode())
        .isEqualTo(200);
  }

  @Test
  void 존재하지_않는_경로는_404_ENDPOINT_NOT_FOUND를_유지한다() {
    assertErrorCode(this.client.get(UNKNOWN), 404, "ENDPOINT_NOT_FOUND");
    assertErrorCode(
        this.client.get(UNKNOWN, HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"),
        404,
        "ENDPOINT_NOT_FOUND");
  }

  @Test
  void 내_정보_경로의_다른_method는_401이_아니라_405다() {
    // securityMatcher에 method를 넣지 않으면 이 요청이 보호 chain으로 들어와 401이 된다.
    assertErrorCode(this.client.send("DELETE", ME, null), 405, "UNSUPPORTED_HTTP_METHOD");
  }

  @Test
  void 회원가입_경로의_GET은_405를_유지한다() {
    assertErrorCode(this.client.get(USERS), 405, "UNSUPPORTED_HTTP_METHOD");
  }
}
