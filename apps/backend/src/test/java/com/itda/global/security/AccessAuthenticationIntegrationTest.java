package com.itda.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.security.AccessTokenTestKeys;
import com.itda.support.security.SecuredTestEndpoints;
import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.application.port.out.AccessTokenClaims;
import com.itda.user.application.port.out.AccessTokenIssuer;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRepository;
import com.itda.user.domain.UserRole;
import com.itda.user.infrastructure.AccessKeyRing;
import com.itda.user.infrastructure.NimbusAccessTokenCodec;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/** 실제 Tomcat으로 Access 인증 경계와 Issue #4 공통 오류 계약의 공존을 검증한다. */
@WebApiTest
class AccessAuthenticationIntegrationTest {

  private static final String SECURED = "/api/v1" + SecuredTestEndpoints.SECURED_PATH;
  private static final String UNAVAILABLE = "/api/v1" + SecuredTestEndpoints.UNAVAILABLE_PATH;
  private static final String CURRENT =
      "/api/v1" + SecuredTestEndpoints.CURRENT_AUTHENTICATION_PATH;
  private static final String UNAVAILABLE_LOOKUPS =
      "/api/v1" + SecuredTestEndpoints.UNAVAILABLE_LOOKUP_COUNT_PATH;
  private static final String HEALTH = "/api/v1/health";
  private static final String UNKNOWN = "/api/v1/no-such-endpoint";

  private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong();

  @LocalServerPort private int port;
  @Autowired private AccessTokenIssuer accessTokenIssuer;
  @Autowired private AccessKeyRing accessKeyRing;
  @Autowired private UserRepository userRepository;
  @Autowired private ApplicationContext applicationContext;

  private HttpTestClient client;
  private long userId;
  private String validToken;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    // nanoTime을 잘라 쓰면 같은 실행 안에서 충돌해 UNIQUE 제약에 걸린다. 단조 증가 수열로 매번 새 계정을 만든다.
    long sequence = ACCOUNT_SEQUENCE.incrementAndGet();
    User user =
        User.create(
            "learner" + sequence,
            "argon2id$hash",
            UserRole.LEARNER,
            Email.of("user" + sequence + "@example.test"),
            now);
    user.verifyEmail(now);
    this.userId = this.userRepository.save(user).getId();
    this.validToken =
        this.accessTokenIssuer
            .issue(new AccessTokenClaims(this.userId, UserRole.LEARNER, 0))
            .compactToken();
  }

  private HttpResponse<String> get(String path, String... headers) {
    return this.client.get(path, headers);
  }

  private HttpResponse<String> getWithToken(String path, String token) {
    return get(path, HttpHeaders.AUTHORIZATION, "Bearer " + token);
  }

  private static void assertUnauthorized(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(HttpTestClient.json(response)).containsEntry("code", "UNAUTHORIZED");
    assertThat(response.headers().allValues(HttpHeaders.WWW_AUTHENTICATE))
        .containsExactly("Bearer");
    assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
  }

  // ---------- 인증 성공 ----------

  @Test
  void 유효한_Access_Token으로_보호_endpoint에_접근한다() {
    HttpResponse<String> response = getWithToken(SECURED, this.validToken);

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body).containsEntry("role", "LEARNER");
    assertThat(((Number) body.get("userId")).longValue()).isEqualTo(this.userId);
    assertThat(body).containsEntry("authorities", "[ROLE_LEARNER]");
    assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
  }

  @Test
  void Authentication에_토큰_원문과_details를_보관하지_않는다() {
    Map<String, Object> body = HttpTestClient.json(getWithToken(SECURED, this.validToken));

    assertThat(body).containsEntry("credentialsPresent", false);
    assertThat(body).containsEntry("detailsPresent", false);
    assertThat((String) body.get("authenticationToString"))
        .doesNotContain(this.validToken)
        .contains("AuthenticatedUser[REDACTED]");
  }

  @Test
  void scheme_대소문자를_구분하지_않는다() {
    assertThat(get(SECURED, HttpHeaders.AUTHORIZATION, "bearer " + this.validToken).statusCode())
        .isEqualTo(200);
  }

  // ---------- 401 ----------

  @Test
  void 토큰이_없으면_401이다() {
    assertUnauthorized(get(SECURED));
  }

  @Test
  void 형식이_어긋난_Authorization은_401이다() {
    assertUnauthorized(get(SECURED, HttpHeaders.AUTHORIZATION, "Bearer"));
    assertUnauthorized(get(SECURED, HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"));
    assertUnauthorized(get(SECURED, HttpHeaders.AUTHORIZATION, "Bearer a b"));
  }

  @Test
  void 위조된_토큰은_401이다() {
    String tampered =
        this.validToken.substring(0, this.validToken.lastIndexOf('.') + 1) + "AAAAAAAAAAAA";

    assertUnauthorized(getWithToken(SECURED, tampered));
  }

  @Test
  void 만료된_토큰은_401이다() {
    NimbusAccessTokenCodec pastCodec =
        new NimbusAccessTokenCodec(
            this.accessKeyRing,
            AccessTokenTestKeys.ISSUER,
            Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));

    assertUnauthorized(
        getWithToken(
            SECURED,
            pastCodec
                .issue(new AccessTokenClaims(this.userId, UserRole.LEARNER, 0))
                .compactToken()));
  }

  @Test
  void 저장소에_없는_사용자의_토큰은_401이다() {
    assertUnauthorized(
        getWithToken(
            SECURED,
            this.accessTokenIssuer
                .issue(new AccessTokenClaims(999_999_999L, UserRole.LEARNER, 0))
                .compactToken()));
  }

  @Test
  void authVersion이_다른_토큰은_401이다() {
    assertUnauthorized(
        getWithToken(
            SECURED,
            this.accessTokenIssuer
                .issue(new AccessTokenClaims(this.userId, UserRole.LEARNER, 1))
                .compactToken()));
  }

  @Test
  void 상한을_넘는_토큰은_401이다() {
    assertUnauthorized(getWithToken(SECURED, "a".repeat(2000)));
  }

  @Test
  void 실패_사유가_달라도_응답이_동일하다() {
    HttpResponse<String> noToken = get(SECURED);
    HttpResponse<String> unknownUser =
        getWithToken(
            SECURED,
            this.accessTokenIssuer
                .issue(new AccessTokenClaims(888_888L, UserRole.LEARNER, 0))
                .compactToken());
    HttpResponse<String> wrongVersion =
        getWithToken(
            SECURED,
            this.accessTokenIssuer
                .issue(new AccessTokenClaims(this.userId, UserRole.LEARNER, 5))
                .compactToken());

    assertThat(noToken.statusCode()).isEqualTo(unknownUser.statusCode()).isEqualTo(401);
    assertThat(wrongVersion.statusCode()).isEqualTo(401);
    assertThat(noToken.body()).isEqualTo(unknownUser.body()).isEqualTo(wrongVersion.body());
    assertThat(noToken.headers().allValues(HttpHeaders.WWW_AUTHENTICATE))
        .isEqualTo(wrongVersion.headers().allValues(HttpHeaders.WWW_AUTHENTICATE));
  }

  @Test
  void JSON을_받지_않는_요청은_401을_유지하고_body만_생략한다() {
    HttpResponse<String> response = get(SECURED, HttpHeaders.ACCEPT, "text/html");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().allValues(HttpHeaders.WWW_AUTHENTICATE))
        .containsExactly("Bearer");
    assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
  }

  // ---------- 503 ----------

  @Test
  void 저장소_상태를_읽지_못하면_503_AUTH_STATE_UNAVAILABLE이다() {
    HttpResponse<String> response = getWithToken(UNAVAILABLE, this.validToken);

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(HttpTestClient.json(response)).containsEntry("code", "AUTH_STATE_UNAVAILABLE");
    assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
  }

  @Test
  void 저장소_장애_응답에_내부_예외와_저장소_정보가_없다() {
    // 이 경로의 실패는 트랜잭션을 시작하지 못한 경우다. 예외 이름·message·SQL이 밖으로 나가면 안 된다.
    HttpResponse<String> response = getWithToken(UNAVAILABLE, this.validToken);

    assertThat(response.body())
        .doesNotContain("Exception")
        .doesNotContain("Transaction")
        .doesNotContain("jdbc")
        .doesNotContain("select");
    assertThat(HttpTestClient.json(response)).containsOnlyKeys("code", "message");
  }

  @Test
  void 저장소_장애_응답에는_WWW_Authenticate를_붙이지_않는다() {
    HttpResponse<String> response = getWithToken(UNAVAILABLE, this.validToken);

    assertThat(response.headers().allValues(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
  }

  @Test
  void 저장소_장애를_401로_바꾸지_않는다() {
    assertThat(getWithToken(UNAVAILABLE, this.validToken).statusCode()).isNotEqualTo(401);
  }

  @Test
  void JSON을_받지_않는_저장소_장애_응답도_503을_유지하고_body만_생략한다() {
    HttpResponse<String> response =
        get(
            UNAVAILABLE,
            HttpHeaders.AUTHORIZATION,
            "Bearer " + this.validToken,
            HttpHeaders.ACCEPT,
            "text/html");

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
    assertThat(response.headers().allValues(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
  }

  @Test
  void ERROR_dispatch에서_인증과_저장소_조회가_다시_실행되지_않는다() {
    // 503은 sendError로 끝나 Tomcat이 /error로 다시 보낸다. 그 dispatch에서 인증 필터가 또 돌면 조회가 2회가 된다.
    long before = lookupCount();

    assertThat(getWithToken(UNAVAILABLE, this.validToken).statusCode()).isEqualTo(503);

    assertThat(lookupCount() - before).isEqualTo(1);
  }

  private long lookupCount() {
    return ((Number) HttpTestClient.json(get(UNAVAILABLE_LOOKUPS)).get("findByIdCallCount"))
        .longValue();
  }

  @Test
  void DB_role과_JWT_role이_달라도_검증된_토큰의_role로_authority를_만든다() {
    // 저장된 사용자는 LEARNER지만 토큰은 ADMIN으로 서명됐다. 계약상 role은 DB 재검증 항목이 아니다.
    String adminToken =
        this.accessTokenIssuer
            .issue(new AccessTokenClaims(this.userId, UserRole.ADMIN, 0))
            .compactToken();

    Map<String, Object> body = HttpTestClient.json(getWithToken(SECURED, adminToken));

    assertThat(body).containsEntry("role", "ADMIN");
    assertThat(body).containsEntry("authorities", "[ROLE_ADMIN]");
  }

  @Test
  void 오류_응답에_토큰과_세대_값이_없다() {
    HttpResponse<String> unauthorized =
        getWithToken(
            SECURED,
            this.accessTokenIssuer
                .issue(new AccessTokenClaims(this.userId, UserRole.LEARNER, 7))
                .compactToken());
    HttpResponse<String> unavailable = getWithToken(UNAVAILABLE, this.validToken);

    assertThat(unauthorized.body()).doesNotContain(this.validToken).doesNotContain("authVersion");
    assertThat(unauthorized.headers().map().toString()).doesNotContain(this.validToken);
    assertThat(unavailable.body()).doesNotContain(this.validToken).doesNotContain("authVersion");
  }

  // ---------- 기존 계약 회귀 ----------

  @Test
  void health는_Authorization과_무관하게_200이다() {
    assertThat(get(HEALTH).statusCode()).isEqualTo(200);
    assertThat(getWithToken(HEALTH, "not-a-valid-token").statusCode()).isEqualTo(200);
    assertThat(get(HEALTH, HttpHeaders.AUTHORIZATION, "Bearer").statusCode()).isEqualTo(200);
    assertThat(getWithToken(HEALTH, this.validToken).statusCode()).isEqualTo(200);
  }

  @Test
  void 존재하지_않는_경로는_Authorization과_무관하게_404다() {
    assertThat(get(UNKNOWN).statusCode()).isEqualTo(404);

    HttpResponse<String> withBadToken = getWithToken(UNKNOWN, "not-a-valid-token");
    assertThat(withBadToken.statusCode()).isEqualTo(404);
    assertThat(HttpTestClient.json(withBadToken)).containsEntry("code", "ENDPOINT_NOT_FOUND");
  }

  @Test
  void 공개_테스트_endpoint는_보호되지_않는다() {
    assertThat(get("/api/v1/test/no-content").statusCode()).isEqualTo(204);
  }

  @Test
  void 기본_사용자_인증_자동_구성을_쓰지_않는다() {
    // UserDetailsServiceAutoConfiguration이 동작하면 기동 로그에 생성된 비밀번호가 찍힌다.
    // 그 자동 구성의 산출물이 컨텍스트에 없다는 것으로 제외가 실제로 적용됐음을 확인한다.
    assertThat(this.applicationContext.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    assertThat(this.applicationContext.getBeanNamesForType(InMemoryUserDetailsManager.class))
        .isEmpty();
  }

  @Test
  void SecurityContext가_다음_요청으로_새지_않는다() {
    assertThat(getWithToken(SECURED, this.validToken).statusCode()).isEqualTo(200);

    Map<String, Object> current = HttpTestClient.json(get(CURRENT));

    assertThat(current).containsEntry("principal", "anonymousUser");
    assertThat(current).doesNotContainEntry("principal", "AuthenticatedUser[REDACTED]");
  }
}
