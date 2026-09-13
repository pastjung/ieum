package com.itda.user.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.application.port.out.AccessTokenClaims;
import com.itda.user.application.port.out.AccessTokenIssuer;
import com.itda.user.application.port.out.CompromisedPasswordCheckResult;
import com.itda.user.application.port.out.CompromisedPasswordChecker;
import com.itda.user.domain.Email;
import com.itda.user.domain.MailOutboxRepository;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRepository;
import com.itda.user.domain.UserRole;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 저장소 장애를 실제 HTTP로 확인한다 (API 명세 3.6.2절 fail-closed).
 *
 * <p>장애 경로를 따로 떼어 놓은 이유는 저장소 bean을 통째로 대역으로 바꿔야 하기 때문이다. 정상 경로 테스트와 같은 클래스에 두면 모든 테스트가 대역 위에서 돌아
 * "운영과 같은 bean으로 검증한다"는 성질을 잃는다.
 *
 * <p><b>운영 코드에 테스트용 분기나 endpoint를 넣지 않는다.</b> 여기서 바꾸는 것은 저장소 어댑터 bean뿐이고, 판정·오류 분류·HTTP 조립은 전부 운영과
 * 같은 코드가 한다.
 */
@WebApiTest
class UserApiUnavailableIntegrationTest {

  private static final String USERS = "/api/v1/users";
  private static final String ME = "/api/v1/users/me";
  private static final String USERNAME = "learner01";
  private static final String EMAIL = "learner01@example.com";
  private static final String VALID_PASSWORD = "충분히 긴 새 비밀번호 문구";
  private static final long USER_ID = 42L;

  /** RFC 4122 UUID v4: 세 번째 그룹이 4로 시작하고 네 번째 그룹이 8·9·a·b 중 하나다. */
  private static final Pattern UUID_V4 =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  @LocalServerPort private int port;
  @Autowired private AccessTokenIssuer accessTokenIssuer;

  @MockitoBean private UserRepository userRepository;
  @MockitoBean private MailOutboxRepository mailOutboxRepository;
  @MockitoBean private CompromisedPasswordChecker compromisedPasswordChecker;

  private HttpTestClient client;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
    when(this.compromisedPasswordChecker.check(any()))
        .thenReturn(CompromisedPasswordCheckResult.NOT_COMPROMISED);
  }

  private static User verifiedUser() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    User user = User.create(USERNAME, "argon2id$hash", UserRole.LEARNER, Email.of(EMAIL), now);
    user.verifyEmail(now);
    return user;
  }

  private String token() {
    return this.accessTokenIssuer.issue(new AccessTokenClaims(USER_ID, UserRole.LEARNER, 0));
  }

  private HttpResponse<String> register() {
    return this.client.send(
        "POST",
        USERS,
        """
        {"username": "%s", "email": "%s", "password": "%s"}
        """
            .formatted(USERNAME, EMAIL, VALID_PASSWORD),
        HttpHeaders.CONTENT_TYPE,
        "application/json");
  }

  /** 503 계약을 한자리에서 본다 — 상태·body 모양·code·추적 헤더·노출 금지. */
  private static void assertUnavailable(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(503);

    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body).containsOnlyKeys("code", "message");
    assertThat(body).containsEntry("code", "AUTH_STATE_UNAVAILABLE");
    assertThat((String) body.get("message")).isNotBlank();

    java.util.List<String> requestIds = response.headers().allValues("X-Request-Id");
    assertThat(requestIds).hasSize(1);
    assertThat(requestIds.get(0)).matches(UUID_V4);
    // 형식만 맞는 상수가 아니라 실제로 생성된 값인지 확인한다.
    assertThat(UUID.fromString(requestIds.get(0)).version()).isEqualTo(4);

    assertThat(response.body())
        .doesNotContain("success")
        .doesNotContain("data")
        .doesNotContain("error")
        .doesNotContain("detail")
        // 저장소·SQL·제약·내부 예외·개인정보가 새지 않는다 (3.7절).
        .doesNotContain("select")
        .doesNotContain("insert")
        .doesNotContain("users")
        .doesNotContain("uk_")
        .doesNotContain("mariadb")
        .doesNotContain("jdbc")
        .doesNotContain("Exception")
        .doesNotContain(USERNAME)
        .doesNotContain(EMAIL);
  }

  // ---------- 회원가입 ----------

  @Test
  void 사용자_저장이_실패하면_503이다() {
    when(this.userRepository.save(any())).thenThrow(new QueryTimeoutException("응답 없음"));

    assertUnavailable(register());
  }

  @Test
  void 메일_작업_접수가_실패하면_503이다() {
    when(this.userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(this.mailOutboxRepository.save(any())).thenThrow(new QueryTimeoutException("응답 없음"));

    // 사용자 INSERT가 성공해도 같은 트랜잭션의 다른 쪽이 실패하면 가입은 성립하지 않는다 (3.2.1절).
    assertUnavailable(register());
  }

  // ---------- 내 정보 ----------

  @Test
  void 인증_상태_조회가_실패하면_401이_아니라_503이다() {
    when(this.userRepository.findById(USER_ID)).thenThrow(new QueryTimeoutException("응답 없음"));

    // 장애를 인증 실패로 위장하면 클라이언트가 재로그인으로 잘못 복구한다 (3.6.2절).
    assertUnavailable(this.client.get(ME, HttpHeaders.AUTHORIZATION, "Bearer " + token()));
  }

  @Test
  void 인증_뒤_요약_조회가_실패하면_503이다() {
    // 첫 조회는 인증 필터, 두 번째 조회는 요약이다. 필터를 통과한 뒤의 실패도 503이어야 한다.
    when(this.userRepository.findById(USER_ID))
        .thenReturn(Optional.of(verifiedUser()))
        .thenThrow(new QueryTimeoutException("응답 없음"));

    assertUnavailable(this.client.get(ME, HttpHeaders.AUTHORIZATION, "Bearer " + token()));
  }
}
