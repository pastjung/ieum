package com.itda.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.domain.Email;
import com.itda.user.domain.MailOutboxEntry;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.ActionTokenStore;
import com.itda.user.repository.MailOutboxJpaRepository;
import com.itda.user.repository.UserRepository;
import com.itda.user.service.ActionTokenClaims;
import com.itda.user.service.EmailVerificationTokenIssuer;
import com.itda.user.service.IssuedActionToken;
import com.itda.user.service.PasswordResetTokenIssuer;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 이메일 인증 두 API의 HTTP 계약을 실제 Tomcat으로 검증한다 (API 명세 3.4.1절).
 *
 * <p>JWT는 운영과 같은 발급·검증기를 쓴다. Redis 상태만 대역으로 바꿔 400·503 분기를 만든다 — Lua 원자성과 실제 상태 전이는 가짜로 증명되지 않으므로
 * 실제 Redis 테스트가 따로 맡는다.
 */
@WebApiTest
class EmailVerificationApiIntegrationTest {

  private static final String REQUESTS = "/api/v1/email-verification-requests";
  private static final String VERIFICATIONS = "/api/v1/email-verifications";
  private static final String HEALTH = "/api/v1/health";
  private static final String UNKNOWN = "/api/v1/no-such-endpoint";

  private static final AtomicLong SEQUENCE = new AtomicLong();

  /** RFC 4122 UUID v4. */
  private static final Pattern UUID_V4 =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  @LocalServerPort private int port;
  @Autowired private UserRepository userRepository;
  @Autowired private MailOutboxJpaRepository mailOutboxJpaRepository;
  @Autowired private EmailVerificationTokenIssuer emailVerificationTokenIssuer;
  @Autowired private PasswordResetTokenIssuer passwordResetTokenIssuer;

  /** Redis 상태만 대역이다. 나머지 경로(JWT·정규화·트랜잭션·저장)는 운영과 같은 bean을 쓴다. */
  @MockitoBean private ActionTokenStore actionTokenStore;

  private HttpTestClient client;
  private String email;

  /**
   * 이 테스트에서 Redis 대역이 소비를 허용할 토큰 해시.
   *
   * <p>대역이 무조건 {@code CONSUMED}를 돌려주면 "제출한 원문이 발급한 원문과 같은가"가 검증되지 않는다. 해시를 비교하게 해 두면 원문을 한 글자라도 가공한
   * 경우 실제 Redis처럼 {@code HASH_MISMATCH}가 된다.
   */
  private final Set<String> consumableTokenHashes = ConcurrentHashMap.newKeySet();

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
    this.email = "user" + SEQUENCE.incrementAndGet() + "@example.test";
    this.consumableTokenHashes.clear();
    when(this.actionTokenStore.consume(any()))
        .thenAnswer(
            invocation -> {
              ActionTokenStore.TokenRecord record = invocation.getArgument(0);
              return this.consumableTokenHashes.contains(record.tokenHash())
                  ? ActionTokenStore.ConsumeResult.CONSUMED
                  : ActionTokenStore.ConsumeResult.HASH_MISMATCH;
            });
  }

  /** 토큰을 발급하고 그 해시만 소비 가능하게 등록한다. */
  private IssuedActionToken issueToken(long userId) {
    IssuedActionToken token =
        this.emailVerificationTokenIssuer.issue(new ActionTokenClaims(userId));
    this.consumableTokenHashes.add(token.tokenHash());
    return token;
  }

  private HttpResponse<String> post(String path, String body) {
    return this.client.send("POST", path, body, HttpHeaders.CONTENT_TYPE, "application/json");
  }

  private HttpResponse<String> request(String emailJson) {
    return post(REQUESTS, "{\"email\": %s}".formatted(emailJson));
  }

  private HttpResponse<String> verify(String tokenJson) {
    return post(VERIFICATIONS, "{\"token\": %s}".formatted(tokenJson));
  }

  private static String quoted(String value) {
    return "\"" + value + "\"";
  }

  private static void assertErrorCode(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    Map<String, Object> body = HttpTestClient.json(response);
    // 공통 오류 body는 최상위 {code, message}뿐이다.
    assertThat(body).containsOnlyKeys("code", "message");
    assertThat(body).containsEntry("code", code);
    assertRequestId(response);
  }

  private static void assertRequestId(HttpResponse<String> response) {
    List<String> requestIds = response.headers().allValues("X-Request-Id");
    assertThat(requestIds).hasSize(1);
    assertThat(requestIds.get(0)).matches(UUID_V4);
    assertThat(UUID.fromString(requestIds.get(0)).version()).isEqualTo(4);
  }

  private static void assertEmptySuccess(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(response.headers().firstValue(HttpHeaders.LOCATION)).isEmpty();
    assertRequestId(response);
  }

  private long registerUser(boolean emailVerified) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    User user =
        User.create(
            "learner" + SEQUENCE.incrementAndGet(),
            "argon2id$hash",
            UserRole.LEARNER,
            Email.of(this.email),
            now);
    if (emailVerified) {
      user.verifyEmail(now);
    }
    return this.userRepository.save(user).getId();
  }

  // ---------- 요청 API: 성공 ----------

  @Test
  void 정상_요청은_202와_빈_body다() {
    assertEmptySuccess(request(quoted(this.email)), 202);
  }

  @Test
  void 요청은_EMAIL_VERIFICATION_작업을_접수한다() {
    request(quoted(this.email));

    assertThat(this.mailOutboxJpaRepository.findAll())
        .filteredOn(entry -> this.email.equals(entry.getRecipientEmail()))
        .singleElement()
        .satisfies(entry -> assertThat(entry.getPurpose().name()).isEqualTo("EMAIL_VERIFICATION"));
  }

  @Test
  void 요청은_trim하고_소문자로_정규화해_저장한다() {
    request(quoted("  " + this.email.toUpperCase() + "  "));

    assertThat(this.mailOutboxJpaRepository.findAll())
        .extracting(MailOutboxEntry::getRecipientEmail)
        .contains(this.email);
  }

  @Test
  void 같은_이메일로_다시_요청해도_202다() {
    assertThat(request(quoted(this.email)).statusCode()).isEqualTo(202);

    assertEmptySuccess(request(quoted(this.email)), 202);
  }

  // ---------- 요청 API: 계정 열거 방지 ----------

  @Test
  void 계정_상태와_무관하게_같은_202를_돌려준다() {
    // 계정 없음
    HttpResponse<String> noAccount = request(quoted(this.email));

    // 미인증 계정
    registerUser(false);
    HttpResponse<String> unverified = request(quoted(this.email));

    // 이미 인증된 계정
    this.email = "verified" + SEQUENCE.incrementAndGet() + "@example.test";
    registerUser(true);
    HttpResponse<String> verified = request(quoted(this.email));

    // 탈퇴 계정
    this.email = "withdrawn" + SEQUENCE.incrementAndGet() + "@example.test";
    long withdrawnId = registerUser(true);
    User withdrawn = this.userRepository.findById(withdrawnId).orElseThrow();
    withdrawn.withdraw(
        "withdrawn-" + withdrawnId,
        Email.of("withdrawn-" + withdrawnId + "@example.invalid"),
        "discarded$hash",
        LocalDateTime.now(ZoneOffset.UTC));
    this.userRepository.save(withdrawn);
    HttpResponse<String> withdrawnResponse = request(quoted(this.email));

    // 상태·body가 모두 같다. 응답만으로 계정 존재·상태를 알 수 없다 (3.4.1절).
    for (HttpResponse<String> response :
        List.of(noAccount, unverified, verified, withdrawnResponse)) {
      assertEmptySuccess(response, 202);
    }
  }

  // ---------- 요청 API: 입력 위반 ----------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"\"", // 빈 문자열
        "\"   \"", // 공백만
        "\"not-an-email\"",
        "\"learner@\"",
        "null", // JSON null
        "123", // 숫자
        "true", // 불리언
        "{}", // 객체
        "[]" // 배열
      })
  void 요청의_형식_위반은_422_INVALID_ACCOUNT_INPUT이다(String emailJson) {
    // 비문자열도 400이 아니라 422다 — 회원가입과 계약이 다르다 (3.4.1절).
    assertErrorCode(request(emailJson), 422, "INVALID_ACCOUNT_INPUT");
  }

  @Test
  void 요청_필드가_없으면_422다() {
    assertErrorCode(post(REQUESTS, "{}"), 422, "INVALID_ACCOUNT_INPUT");
  }

  @Test
  void 요청의_정규화_후_254자_경계를_지킨다() {
    String domain = "@example.test";
    String max = "a".repeat(254 - domain.length()) + domain;
    String over = "a".repeat(255 - domain.length()) + domain;

    assertThat(request(quoted(max)).statusCode()).isEqualTo(202);
    assertErrorCode(request(quoted(over)), 422, "INVALID_ACCOUNT_INPUT");
  }

  @Test
  void 요청의_JSON_문법_오류는_400_MALFORMED_REQUEST_BODY다() {
    assertErrorCode(post(REQUESTS, "{\"email\": "), 400, "MALFORMED_REQUEST_BODY");
    assertErrorCode(post(REQUESTS, null), 400, "MALFORMED_REQUEST_BODY");
  }

  @Test
  void 요청_오류_응답에_입력값이_없다() {
    String rejected = "not-an-email-" + this.email;

    assertThat(request(quoted(rejected)).body())
        .doesNotContain(rejected)
        .doesNotContain(this.email);
  }

  // ---------- 완료 API: 성공 ----------

  @Test
  void 정상_토큰은_204와_빈_body다() {
    long userId = registerUser(false);
    IssuedActionToken token = issueToken(userId);

    assertEmptySuccess(verify(quoted(token.compactToken())), 204);

    assertThat(this.userRepository.findById(userId).orElseThrow().isEmailVerified()).isTrue();
  }

  @Test
  void 완료_응답에_토큰도_쿠키도_없다() {
    long userId = registerUser(false);
    IssuedActionToken token = issueToken(userId);

    HttpResponse<String> response = verify(quoted(token.compactToken()));

    assertThat(response.body()).isEmpty();
    assertThat(response.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(response.headers().allValues(HttpHeaders.AUTHORIZATION)).isEmpty();
  }

  // ---------- 완료 API: 입력 위반 (422) ----------

  @ParameterizedTest
  @ValueSource(strings = {"\"\"", "\"   \"", "null", "123", "true", "{}", "[]"})
  void 완료의_형식_위반은_422_INVALID_ACCOUNT_INPUT이다(String tokenJson) {
    assertErrorCode(verify(tokenJson), 422, "INVALID_ACCOUNT_INPUT");
  }

  @Test
  void 완료_필드가_없으면_422다() {
    assertErrorCode(post(VERIFICATIONS, "{}"), 422, "INVALID_ACCOUNT_INPUT");
  }

  @Test
  void 완료의_JSON_문법_오류는_400_MALFORMED_REQUEST_BODY다() {
    assertErrorCode(post(VERIFICATIONS, "{\"token\": "), 400, "MALFORMED_REQUEST_BODY");
    assertErrorCode(post(VERIFICATIONS, null), 400, "MALFORMED_REQUEST_BODY");
  }

  // ---------- 완료 API: 토큰 부적격 (400) ----------

  @Test
  void 앞뒤_공백이_붙은_토큰을_trim하지_않아_400이_된다() {
    long userId = registerUser(false);
    IssuedActionToken token = issueToken(userId);

    // trim했다면 204가 된다. opaque 값을 가공하지 않는다는 계약이 여기서 드러난다 (3.4.1절).
    assertErrorCode(verify(quoted(" " + token.compactToken() + " ")), 400, "ACTION_TOKEN_INVALID");
    assertThat(this.userRepository.findById(userId).orElseThrow().isEmailVerified()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-a-jwt",
        "a.b.c",
        "eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0." // alg=none
      })
  void 위조_토큰은_400_ACTION_TOKEN_INVALID다(String rawToken) {
    assertErrorCode(verify(quoted(rawToken)), 400, "ACTION_TOKEN_INVALID");
  }

  @Test
  void 서명을_바꾼_토큰은_400이다() {
    long userId = registerUser(false);
    String compact =
        this.emailVerificationTokenIssuer.issue(new ActionTokenClaims(userId)).compactToken();
    String tampered = compact.substring(0, compact.lastIndexOf('.') + 1) + "AAAAAAAAAAAAAAAAAAAAAA";

    assertErrorCode(verify(quoted(tampered)), 400, "ACTION_TOKEN_INVALID");
  }

  @Test
  void 다른_종류의_토큰은_400이다() {
    long userId = registerUser(false);
    IssuedActionToken resetToken =
        this.passwordResetTokenIssuer.issue(new ActionTokenClaims(userId));

    // typ·aud·purpose가 모두 어긋난다.
    assertErrorCode(verify(quoted(resetToken.compactToken())), 400, "ACTION_TOKEN_INVALID");
  }

  @Test
  void 존재하지_않는_사용자의_토큰은_400이다() {
    IssuedActionToken token = issueToken(987_654_321L);

    assertErrorCode(verify(quoted(token.compactToken())), 400, "ACTION_TOKEN_INVALID");
  }

  @Test
  void 탈퇴한_사용자의_토큰은_400이다() {
    long userId = registerUser(true);
    User user = this.userRepository.findById(userId).orElseThrow();
    user.withdraw(
        "withdrawn-" + userId,
        Email.of("withdrawn-" + userId + "@example.invalid"),
        "discarded$hash",
        LocalDateTime.now(ZoneOffset.UTC));
    this.userRepository.save(user);
    IssuedActionToken token = issueToken(userId);

    assertErrorCode(verify(quoted(token.compactToken())), 400, "ACTION_TOKEN_INVALID");
  }

  @Test
  void Redis_부적격_결과는_400이다() {
    long userId = registerUser(false);
    IssuedActionToken token = issueToken(userId);
    // 이미 Answer로 stub된 mock이라 when(mock.consume(...))을 다시 쓰면 stubbing 중에 Answer가 실행된다.
    doReturn(ActionTokenStore.ConsumeResult.ALREADY_CONSUMED)
        .when(this.actionTokenStore)
        .consume(any());

    assertErrorCode(verify(quoted(token.compactToken())), 400, "ACTION_TOKEN_INVALID");
    assertThat(this.userRepository.findById(userId).orElseThrow().isEmailVerified()).isFalse();
  }

  // ---------- 완료 API: 저장소 장애 (503) ----------

  @Test
  void Redis_결과_불확실은_503_AUTH_STATE_UNAVAILABLE이다() {
    long userId = registerUser(false);
    IssuedActionToken token = issueToken(userId);
    doReturn(ActionTokenStore.ConsumeResult.UNAVAILABLE).when(this.actionTokenStore).consume(any());

    assertErrorCode(verify(quoted(token.compactToken())), 503, "AUTH_STATE_UNAVAILABLE");
    assertThat(this.userRepository.findById(userId).orElseThrow().isEmailVerified()).isFalse();
  }

  @Test
  void 완료_오류_응답에_토큰이_없다() {
    long userId = registerUser(false);
    String compact =
        this.emailVerificationTokenIssuer.issue(new ActionTokenClaims(userId)).compactToken();
    doReturn(ActionTokenStore.ConsumeResult.NOT_FOUND).when(this.actionTokenStore).consume(any());

    HttpResponse<String> response = verify(quoted(compact));

    assertThat(response.body())
        .doesNotContain(compact)
        .doesNotContain(Long.toString(userId))
        .doesNotContain("jti")
        .doesNotContain("redis")
        .doesNotContain("Exception");
  }

  // ---------- 보안·HTTP 회귀 ----------

  @Test
  void 두_endpoint는_Authorization_없이_열려_있다() {
    assertThat(request(quoted(this.email)).statusCode()).isEqualTo(202);
    assertErrorCode(verify(quoted("not-a-jwt")), 400, "ACTION_TOKEN_INVALID");
  }

  @Test
  void 잘못된_토큰_헤더가_있어도_공개_경로는_영향받지_않는다() {
    HttpResponse<String> response =
        this.client.send(
            "POST",
            REQUESTS,
            "{\"email\": %s}".formatted(quoted(this.email)),
            HttpHeaders.CONTENT_TYPE,
            "application/json",
            HttpHeaders.AUTHORIZATION,
            "Bearer not-a-jwt");

    assertThat(response.statusCode()).isEqualTo(202);
    assertThat(this.client.get(HEALTH, HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt").statusCode())
        .isEqualTo(200);
  }

  @Test
  void 지원하지_않는_method는_405다() {
    assertErrorCode(this.client.get(REQUESTS), 405, "UNSUPPORTED_HTTP_METHOD");
    assertErrorCode(this.client.get(VERIFICATIONS), 405, "UNSUPPORTED_HTTP_METHOD");
  }

  @Test
  void 존재하지_않는_경로는_404를_유지한다() {
    assertErrorCode(this.client.get(UNKNOWN), 404, "ENDPOINT_NOT_FOUND");
  }

  @Test
  void 보호_경로는_여전히_401이다() {
    // 이 이슈가 /users/me 보호 matcher를 느슨하게 만들지 않았다는 회귀 확인이다.
    assertErrorCode(this.client.get("/api/v1/users/me"), 401, "UNAUTHORIZED");
  }
}
