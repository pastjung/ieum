package com.itda.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.itda.global.error.GlobalExceptionHandler;
import com.itda.global.web.RequestTracingFilter;
import com.itda.support.log.CapturingAppender;
import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.domain.Email;
import com.itda.user.domain.PasswordPolicy;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.domain.UserStatus;
import com.itda.user.repository.ActionTokenStore;
import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserRepository;
import com.itda.user.service.AccessTokenIssuer;
import com.itda.user.service.ActionTokenClaims;
import com.itda.user.service.CompromisedPasswordChecker;
import com.itda.user.service.PasswordHasher;
import com.itda.user.service.PasswordResetTokenIssuer;
import com.itda.user.service.RefreshTokenIssuer;
import com.itda.user.service.UserWithdrawalService;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 회원 탈퇴 API의 HTTP·인증 계약을 실제 Tomcat으로 검증한다 (API 명세 3.2.1·3.6.1·3.6.2절).
 *
 * <p>JWT·Argon2id·H2·Access/Refresh 검증은 운영과 같은 bean을 쓴다. Redis 상태와 외부 유출 조회만 대역이다 — 실제 계열 폐기는 Redis
 * 테스트가, 조건부 UPDATE 경합·UNIQUE 충돌·연결 데이터 보존은 실제 MariaDB 테스트가 맡는다.
 */
@WebApiTest
class UserWithdrawalApiIntegrationTest {

  private static final String WITHDRAWALS = "/api/v1/users/me/withdrawals";
  private static final String ME = "/api/v1/users/me";
  private static final String USERS = "/api/v1/users";
  private static final String ACCESS_TOKENS = "/api/v1/access-tokens";
  private static final String RESETS = "/api/v1/password-resets";

  private static final String PASSWORD = "충분히 긴 현재 비밀번호 문구";
  private static final String NEW_PASSWORD = "충분히 긴 새 비밀번호 문구";

  private static final AtomicLong SEQUENCE = new AtomicLong();

  /** RFC 4122 UUID v4. */
  private static final Pattern UUID_V4 =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  private static final Pattern PSEUDONYM_USERNAME = Pattern.compile("^withdrawn-[0-9a-f]{32}$");
  private static final Pattern PSEUDONYM_EMAIL =
      Pattern.compile("^withdrawn-[0-9a-f]{32}@withdrawn\\.invalid$");

  @LocalServerPort private int port;
  @Autowired private UserRepository userRepository;
  @Autowired private AccessTokenIssuer accessTokenIssuer;
  @Autowired private RefreshTokenIssuer refreshTokenIssuer;
  @Autowired private PasswordResetTokenIssuer passwordResetTokenIssuer;
  @Autowired private PasswordHasher passwordHasher;

  /** Redis 상태만 대역이다. 나머지 경로는 운영과 같은 bean을 쓴다. */
  @MockitoBean private RefreshTokenStore refreshTokenStore;

  @MockitoBean private ActionTokenStore actionTokenStore;

  /** 재가입 확인에서 외부 유출 조회 API를 호출하지 않는다. */
  @MockitoBean private CompromisedPasswordChecker compromisedPasswordChecker;

  private HttpTestClient client;
  private String username;
  private String email;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
    // 같은 H2를 공유하는 다른 통합 테스트와 겹치지 않도록 클래스마다 다른 접두사를 쓴다.
    long sequence = SEQUENCE.incrementAndGet();
    this.username = "withdrawer" + sequence;
    this.email = "withdrawer" + sequence + "@example.test";
    when(this.refreshTokenStore.revokeAllFamilies(anyLong()))
        .thenReturn(RefreshTokenStore.AllFamiliesRevocationResult.REVOKED);
    when(this.refreshTokenStore.lookup(any())).thenReturn(RefreshTokenStore.LookupResult.ACTIVE);
    when(this.refreshTokenStore.rotate(any(), any()))
        .thenReturn(RefreshTokenStore.RotationResult.ROTATED);
    when(this.actionTokenStore.lookup(any())).thenReturn(ActionTokenStore.LookupResult.ACTIVE);
    when(this.actionTokenStore.consume(any())).thenReturn(ActionTokenStore.ConsumeResult.CONSUMED);
    when(this.compromisedPasswordChecker.check(any()))
        .thenReturn(CompromisedPasswordChecker.CheckResult.NOT_COMPROMISED);
  }

  // ---------- 준비 ----------

  private long registerUser(boolean emailVerified, String password) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    User user =
        User.create(
            this.username,
            this.passwordHasher.hash(PasswordPolicy.normalize(password)).value(),
            UserRole.LEARNER,
            Email.of(this.email),
            now);
    if (emailVerified) {
      user.verifyEmail(now);
    }
    return this.userRepository.save(user).getId();
  }

  private long registerUser() {
    return registerUser(true, PASSWORD);
  }

  private String accessToken(long userId) {
    return this.accessTokenIssuer
        .issue(new AccessTokenIssuer.Claims(userId, UserRole.LEARNER, 0))
        .compactToken();
  }

  private User reload(long userId) {
    return this.userRepository.findById(userId).orElseThrow();
  }

  // ---------- 요청 도우미 ----------

  private HttpResponse<String> post(String path, String body, String... extraHeaders) {
    String[] headers = new String[2 + extraHeaders.length];
    headers[0] = HttpHeaders.CONTENT_TYPE;
    headers[1] = "application/json";
    System.arraycopy(extraHeaders, 0, headers, 2, extraHeaders.length);
    return this.client.send("POST", path, body, headers);
  }

  private HttpResponse<String> withdrawBody(
      String accessToken, String body, String... extraHeaders) {
    String[] headers = new String[2 + extraHeaders.length];
    headers[0] = HttpHeaders.AUTHORIZATION;
    headers[1] = "Bearer " + accessToken;
    System.arraycopy(extraHeaders, 0, headers, 2, extraHeaders.length);
    return post(WITHDRAWALS, body, headers);
  }

  private HttpResponse<String> withdraw(String accessToken, String currentPassword) {
    return withdrawBody(
        accessToken, "{\"currentPassword\": %s}".formatted(quoted(currentPassword)));
  }

  private static String quoted(String value) {
    return "\"" + value + "\"";
  }

  private static void assertErrorCode(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body).containsOnlyKeys("code", "message");
    assertThat(body).containsEntry("code", code);
    assertThat(response.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
    assertRequestId(response);
  }

  private static void assertRequestId(HttpResponse<String> response) {
    List<String> requestIds = response.headers().allValues("X-Request-Id");
    assertThat(requestIds).hasSize(1);
    assertThat(requestIds.get(0)).matches(UUID_V4);
    assertThat(UUID.fromString(requestIds.get(0)).version()).isEqualTo(4);
  }

  /** 성공은 완전히 빈 body다. 토큰·쿠키·위치를 내보내지 않는다. */
  private static void assertEmptySuccess(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(response.headers().firstValue(HttpHeaders.LOCATION)).isEmpty();
    assertThat(response.headers().firstValue(HttpHeaders.AUTHORIZATION)).isEmpty();
    assertRequestId(response);
  }

  private void assertStillActive(long userId) {
    User user = reload(userId);
    assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(user.getUsername()).isEqualTo(this.username);
    assertThat(user.getAuthVersion()).isZero();
  }

  // ---------- 성공 ----------

  @Test
  void 현재_비밀번호가_맞으면_204와_빈_body이고_토큰도_쿠키도_발급하지_않는다() {
    long userId = registerUser();

    assertEmptySuccess(withdraw(accessToken(userId), PASSWORD));
  }

  @Test
  void 성공하면_행을_남기고_탈퇴_상태_가명_폐기_해시_auth_version을_확정한다() {
    long userId = registerUser();
    User before = reload(userId);

    withdraw(accessToken(userId), PASSWORD);

    User after = reload(userId);
    assertThat(after.getId()).isEqualTo(userId);
    assertThat(after.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    assertThat(after.getWithdrawnAt()).isNotNull();
    assertThat(after.getEmailVerifiedAt()).isNull();
    assertThat(after.getUsername()).matches(PSEUDONYM_USERNAME);
    assertThat(after.getEmail().getValue()).matches(PSEUDONYM_EMAIL);
    assertThat(after.getPasswordHash())
        .startsWith("$argon2id$")
        .isNotEqualTo(before.getPasswordHash());
    assertThat(
            this.passwordHasher.matches(
                PasswordPolicy.normalize(PASSWORD), after.getPasswordHash()))
        .isFalse();
    assertThat(after.getAuthVersion()).isEqualTo(before.getAuthVersion() + 1);
    assertThat(after.getRole()).isEqualTo(before.getRole());
    assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
    assertThat(after.getFailedLoginCount()).isEqualTo(before.getFailedLoginCount());
    assertThat(after.getLockedUntil()).isEqualTo(before.getLockedUntil());
  }

  @Test
  void 성공하면_그_사용자의_Refresh_계열_정리를_요청한다() {
    long userId = registerUser();

    withdraw(accessToken(userId), PASSWORD);

    verify(this.refreshTokenStore).revokeAllFamilies(userId);
  }

  @Test
  void 계열_정리가_끝나지_않아도_204다() {
    long userId = registerUser();
    when(this.refreshTokenStore.revokeAllFamilies(anyLong()))
        .thenReturn(RefreshTokenStore.AllFamiliesRevocationResult.UNAVAILABLE);

    assertEmptySuccess(withdraw(accessToken(userId), PASSWORD));
    assertThat(reload(userId).getStatus()).isEqualTo(UserStatus.WITHDRAWN);
  }

  @Test
  void 계열_정리가_예외로_끝나도_204다() {
    long userId = registerUser();
    doThrow(new IllegalStateException("정리 실패"))
        .when(this.refreshTokenStore)
        .revokeAllFamilies(anyLong());

    assertEmptySuccess(withdraw(accessToken(userId), PASSWORD));
    assertThat(reload(userId).getStatus()).isEqualTo(UserStatus.WITHDRAWN);
  }

  @Test
  void 원래_username과_email로_즉시_다시_가입할_수_있고_새_id를_받는다() {
    long userId = registerUser();
    withdraw(accessToken(userId), PASSWORD);

    HttpResponse<String> registered =
        post(
            USERS,
            "{\"username\": %s, \"email\": %s, \"password\": %s}"
                .formatted(quoted(this.username), quoted(this.email), quoted(NEW_PASSWORD)));

    assertThat(registered.statusCode()).isEqualTo(201);
    Map<String, Object> body = HttpTestClient.json(registered);
    assertThat(body).containsEntry("username", this.username).containsEntry("email", this.email);
    assertThat(body.get("userId")).isNotEqualTo(Long.toString(userId));
    assertThat(reload(userId).getStatus()).isEqualTo(UserStatus.WITHDRAWN);
  }

  @Test
  void body의_사용자_식별자는_무시하고_인증된_사용자만_탈퇴한다() {
    long other = registerUser();
    this.username = "withdrawer" + SEQUENCE.incrementAndGet();
    this.email = this.username + "@example.test";
    long self = registerUser();

    assertEmptySuccess(
        withdrawBody(
            accessToken(self),
            "{\"currentPassword\": %s, \"userId\": %d}".formatted(quoted(PASSWORD), other)));

    assertThat(reload(self).getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    assertThat(reload(other).getStatus()).isEqualTo(UserStatus.ACTIVE);
  }

  @Test
  void X_Client_Platform과_쿠키를_읽지_않는다() {
    long userId = registerUser();

    assertEmptySuccess(
        withdrawBody(
            accessToken(userId),
            "{\"currentPassword\": %s}".formatted(quoted(PASSWORD)),
            "X-Client-Platform",
            "NOT-A-PLATFORM",
            "Cookie",
            "__Host-refreshToken=not-a-token"));
  }

  // ---------- 탈퇴 뒤 기존 인증 ----------

  @Test
  void 성공_뒤_기존_Access_Token은_보호_API에서_401이다() {
    long userId = registerUser();
    String accessToken = accessToken(userId);
    assertThat(this.client.get(ME, HttpHeaders.AUTHORIZATION, "Bearer " + accessToken).statusCode())
        .isEqualTo(200);

    assertEmptySuccess(withdraw(accessToken, PASSWORD));

    assertErrorCode(
        this.client.get(ME, HttpHeaders.AUTHORIZATION, "Bearer " + accessToken),
        401,
        "UNAUTHORIZED");
    assertErrorCode(withdraw(accessToken, PASSWORD), 401, "UNAUTHORIZED");
  }

  @Test
  void 성공_뒤_기존_Refresh_Token은_갱신에서_401이다() {
    long userId = registerUser();
    String refreshToken =
        this.refreshTokenIssuer
            .issue(new RefreshTokenIssuer.Claims(userId, UUID.randomUUID().toString(), 0))
            .compactToken();
    String refreshBody = "{\"refreshToken\": \"" + refreshToken + "\"}";
    assertThat(post(ACCESS_TOKENS, refreshBody, "X-Client-Platform", "MOBILE").statusCode())
        .isEqualTo(201);

    assertEmptySuccess(withdraw(accessToken(userId), PASSWORD));

    // Redis 대역은 여전히 ACTIVE를 돌려준다. 거부 근거는 DB status·auth_version뿐이다 (3.6.1절).
    assertErrorCode(
        post(ACCESS_TOKENS, refreshBody, "X-Client-Platform", "MOBILE"),
        401,
        "INVALID_REFRESH_TOKEN");
  }

  @Test
  void 성공_뒤_남은_비밀번호_재설정_토큰은_400이다() {
    long userId = registerUser();
    String resetToken =
        this.passwordResetTokenIssuer.issue(new ActionTokenClaims(userId)).compactToken();

    assertEmptySuccess(withdraw(accessToken(userId), PASSWORD));

    // Redis 대역은 ACTIVE·CONSUMED를 돌려준다. 거부 근거는 DB status=ACTIVE 확인뿐이다.
    assertErrorCode(
        post(
            RESETS,
            "{\"token\": %s, \"newPassword\": %s}"
                .formatted(quoted(resetToken), quoted(NEW_PASSWORD))),
        400,
        "ACTION_TOKEN_INVALID");
    assertThat(reload(userId).getStatus()).isEqualTo(UserStatus.WITHDRAWN);
  }

  // ---------- 인증 실패 (401) ----------

  @Test
  void Access_Token이_없으면_401이고_아무것도_바꾸지_않는다() {
    long userId = registerUser();

    assertErrorCode(
        post(WITHDRAWALS, "{\"currentPassword\": %s}".formatted(quoted(PASSWORD))),
        401,
        "UNAUTHORIZED");

    assertStillActive(userId);
    verify(this.refreshTokenStore, never()).revokeAllFamilies(anyLong());
  }

  @Test
  void 잘못된_Access_Token은_401이다() {
    long userId = registerUser();

    assertErrorCode(withdraw("not-a-jwt", PASSWORD), 401, "UNAUTHORIZED");

    assertStillActive(userId);
  }

  @Test
  void 이메일_미인증_계정의_토큰은_401이다() {
    long userId = registerUser(false, PASSWORD);

    assertErrorCode(withdraw(accessToken(userId), PASSWORD), 401, "UNAUTHORIZED");

    assertThat(reload(userId).getStatus()).isEqualTo(UserStatus.ACTIVE);
  }

  // ---------- 현재 비밀번호 불일치 (403) ----------

  @Test
  void 현재_비밀번호가_다르면_403이고_아무것도_바꾸지_않는다() {
    long userId = registerUser();
    User before = reload(userId);

    assertErrorCode(withdraw(accessToken(userId), NEW_PASSWORD), 403, "CURRENT_PASSWORD_MISMATCH");

    User after = reload(userId);
    assertStillActive(userId);
    assertThat(after.getPasswordHash()).isEqualTo(before.getPasswordHash());
    assertThat(after.getFailedLoginCount()).isEqualTo(before.getFailedLoginCount());
    assertThat(after.getRowVersion()).isEqualTo(before.getRowVersion());
    verify(this.refreshTokenStore, never()).revokeAllFamilies(anyLong());
  }

  @Test
  void 현재_비밀번호를_trim하지_않는다() {
    String spacedPassword = "  " + PASSWORD + "  ";
    long userId = registerUser(true, spacedPassword);

    assertErrorCode(withdraw(accessToken(userId), PASSWORD), 403, "CURRENT_PASSWORD_MISMATCH");
    assertStillActive(userId);

    assertEmptySuccess(withdraw(accessToken(userId), spacedPassword));
  }

  @Test
  void 공백만_있는_값은_누락이_아니라_비교_결과인_403이다() {
    long userId = registerUser();

    assertErrorCode(withdraw(accessToken(userId), "   "), 403, "CURRENT_PASSWORD_MISMATCH");
  }

  // ---------- 입력 위반 ----------

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"currentPassword\": null}", "{\"currentPassword\": \"\"}"})
  void 누락_null_빈_문자열은_422_INVALID_ACCOUNT_INPUT이다(String body) {
    long userId = registerUser();

    assertErrorCode(withdrawBody(accessToken(userId), body), 422, "INVALID_ACCOUNT_INPUT");

    assertStillActive(userId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"123", "true", "{}", "[]", "1.5"})
  void 비문자열_값은_400_MALFORMED_REQUEST_BODY다(String value) {
    long userId = registerUser();

    assertErrorCode(
        withdrawBody(accessToken(userId), "{\"currentPassword\": %s}".formatted(value)),
        400,
        "MALFORMED_REQUEST_BODY");

    assertStillActive(userId);
  }

  @Test
  void JSON_문법_오류는_400_MALFORMED_REQUEST_BODY다() {
    long userId = registerUser();

    assertErrorCode(
        withdrawBody(accessToken(userId), "{\"currentPassword\": "), 400, "MALFORMED_REQUEST_BODY");

    assertStillActive(userId);
  }

  // ---------- 노출 ----------

  @Test
  void 오류_응답에_비밀번호_해시_식별자_내부_정보가_없다() {
    long userId = registerUser();
    String storedHash = reload(userId).getPasswordHash();

    for (HttpResponse<String> response :
        List.of(
            withdraw(accessToken(userId), NEW_PASSWORD),
            withdrawBody(accessToken(userId), "{\"currentPassword\": 123}"),
            withdrawBody(accessToken(userId), "{}"))) {
      assertThat(response.body())
          .doesNotContain(NEW_PASSWORD)
          .doesNotContain(PASSWORD)
          .doesNotContain(storedHash)
          .doesNotContain(this.username)
          .doesNotContain(this.email)
          .doesNotContain(Long.toString(userId))
          .doesNotContain("Exception")
          .doesNotContain("sql");
    }
  }

  @Test
  void 처리_로그에_비밀번호_해시_식별자_가명_토큰이_없다() {
    List<CapturingAppender> appenders =
        List.of(
            CapturingAppender.attachTo(UserWithdrawalService.class),
            CapturingAppender.attachTo(GlobalExceptionHandler.class),
            CapturingAppender.attachTo(RequestTracingFilter.class));
    try {
      long userId = registerUser();
      String accessToken = accessToken(userId);
      String storedHash = reload(userId).getPasswordHash();
      withdraw(accessToken, NEW_PASSWORD);
      withdrawBody(accessToken, "{\"currentPassword\": 123}");
      when(this.refreshTokenStore.revokeAllFamilies(anyLong()))
          .thenReturn(RefreshTokenStore.AllFamiliesRevocationResult.UNAVAILABLE);
      assertEmptySuccess(withdraw(accessToken, PASSWORD));
      User withdrawn = reload(userId);

      // 계열 정리 실패는 운영 경고로 남는다. 요청 로그만으로 이 단언이 통과하지 않도록 서비스 logger만 본다.
      assertThat(appenders.get(0).events())
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("stage=unavailable");
              });
      List<ILoggingEvent> logged = new ArrayList<>();
      appenders.forEach(appender -> logged.addAll(appender.events()));
      for (ILoggingEvent event : logged) {
        assertThat(event.getFormattedMessage())
            .doesNotContain(PASSWORD)
            .doesNotContain(NEW_PASSWORD)
            .doesNotContain(storedHash)
            .doesNotContain(withdrawn.getPasswordHash())
            .doesNotContain(this.username)
            .doesNotContain(this.email)
            .doesNotContain(withdrawn.getUsername())
            .doesNotContain(withdrawn.getEmail().getValue())
            .doesNotContain(accessToken);
      }
    } finally {
      appenders.forEach(CapturingAppender::detach);
    }
  }

  // ---------- 보안·HTTP 회귀 ----------

  @Test
  void 보호_matcher는_정확한_POST_경로만_추가한다() {
    // 다른 method는 보호 chain에 들어가지 않아 401이 아니라 405다.
    assertErrorCode(this.client.get(WITHDRAWALS), 405, "UNSUPPORTED_HTTP_METHOD");
    // 하위 경로는 존재하지 않는 경로로 남는다.
    assertErrorCode(post(WITHDRAWALS + "/extra", "{}"), 404, "ENDPOINT_NOT_FOUND");
    // 기존 보호 경로는 그대로다.
    assertErrorCode(this.client.get(ME), 401, "UNAUTHORIZED");
  }
}
