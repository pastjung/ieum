package com.itda.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.global.error.ErrorCode;
import com.itda.global.error.ErrorCodeAttribute;
import com.itda.support.security.StubAccessTokenVerifier;
import com.itda.support.security.StubUserRepository;
import com.itda.user.application.AccessAuthenticationService;
import com.itda.user.application.port.out.AccessTokenVerification;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 필터가 자기 손으로 {@code SecurityContext}를 비우는지 직접 검증한다.
 *
 * <p>통합 테스트로는 이 책임을 고정할 수 없다. 실제 chain에서는 앞단의 {@code SecurityContextHolderFilter}가 요청마다 holder를
 * 덮어쓰고 비우므로, 이 필터의 {@code finally} 블록을 통째로 지워도 통합 테스트는 그대로 통과한다. 게다가 두 HTTP 요청이 같은 워커 스레드에 배정된다는
 * 보장도 없어 전제 자체가 비결정적이다. 그래서 여기서는 필터만 떼어 직접 호출한다.
 *
 * <p>테스트 값에는 실제 토큰·서명 key를 쓰지 않는다. 검증기는 스텁이라 토큰 문자열의 내용이 쓰이지 않는다.
 */
class AccessTokenAuthenticationFilterTest {

  private static final long USER_ID = 42L;
  private static final String SAMPLE = "test-compact-value";
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 12, 12, 0);

  private StubAccessTokenVerifier verifier;
  private StubUserRepository userRepository;
  private AccessTokenAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
    this.verifier = new StubAccessTokenVerifier();
    this.userRepository = new StubUserRepository();
    this.filter =
        new AccessTokenAuthenticationFilter(
            new AccessAuthenticationService(this.verifier, this.userRepository));
  }

  @AfterEach
  void tearDown() {
    // 다른 테스트로 상태가 새지 않게 한다. 이 테스트가 검증하려는 성질과 무관한 격리 조치다.
    SecurityContextHolder.clearContext();
  }

  private void authenticatedUserExists() {
    User user =
        User.create("learner01", "argon2id$hash", UserRole.LEARNER, Email.of("a@b.com"), NOW);
    user.verifyEmail(NOW);
    this.userRepository.register(USER_ID, user);
    this.verifier.willReturn(new AccessTokenVerification.Verified(USER_ID, UserRole.LEARNER, 0));
  }

  private static MockHttpServletRequest requestWithBearer() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test/secured");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + SAMPLE);
    return request;
  }

  private static Authentication currentAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication();
  }

  @Test
  void downstream_처리_중에는_인증_정보가_SecurityContext에_있다() throws ServletException, IOException {
    authenticatedUserExists();
    AtomicReference<Authentication> seenDownstream = new AtomicReference<>();
    FilterChain chain = (request, response) -> seenDownstream.set(currentAuthentication());

    this.filter.doFilter(requestWithBearer(), new MockHttpServletResponse(), chain);

    assertThat(seenDownstream.get()).isNotNull();
    assertThat(seenDownstream.get().isAuthenticated()).isTrue();
    assertThat(seenDownstream.get().getPrincipal())
        .isEqualTo(new AuthenticatedUser(USER_ID, UserRole.LEARNER));
  }

  @Test
  void doFilter가_정상_반환하면_SecurityContext가_비어_있다() throws ServletException, IOException {
    authenticatedUserExists();

    this.filter.doFilter(requestWithBearer(), new MockHttpServletResponse(), new MockFilterChain());

    assertThat(currentAuthentication()).isNull();
  }

  @Test
  void downstream이_예외를_던져도_SecurityContext가_비워진다() {
    authenticatedUserExists();
    FilterChain failingChain =
        (request, response) -> {
          assertThat(currentAuthentication()).isNotNull();
          throw new IllegalStateException("downstream 실패");
        };

    assertThatThrownBy(
            () ->
                this.filter.doFilter(
                    requestWithBearer(), new MockHttpServletResponse(), failingChain))
        .isInstanceOf(IllegalStateException.class);

    assertThat(currentAuthentication()).isNull();
  }

  @Test
  void 인증하지_않은_요청은_SecurityContext를_건드리지_않는다() throws ServletException, IOException {
    // 토큰이 없으면 익명으로 통과시킨다. 401 판정은 AuthorizationFilter의 몫이다.
    MockHttpServletResponse response = new MockHttpServletResponse();

    this.filter.doFilter(
        new MockHttpServletRequest("GET", "/test/secured"), response, new MockFilterChain());

    assertThat(currentAuthentication()).isNull();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
  }

  @Test
  void 검증에_실패한_토큰도_인증하지_않고_통과시킨다() throws ServletException, IOException {
    this.verifier.willReturn(new AccessTokenVerification.Invalid());
    MockHttpServletResponse response = new MockHttpServletResponse();

    this.filter.doFilter(requestWithBearer(), response, new MockFilterChain());

    assertThat(currentAuthentication()).isNull();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
  }

  @Test
  void 저장소_장애는_downstream으로_보내지_않고_503으로_끊는다() throws ServletException, IOException {
    this.verifier.willReturn(new AccessTokenVerification.Verified(USER_ID, UserRole.LEARNER, 0));
    this.userRepository.willFail();
    MockHttpServletRequest request = requestWithBearer();
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<Boolean> downstreamCalled = new AtomicReference<>(false);
    FilterChain chain = (req, res) -> downstreamCalled.set(true);

    this.filter.doFilter(request, response, chain);

    assertThat(downstreamCalled.get()).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    assertThat(response.getErrorMessage()).isNull();
    assertThat(currentAuthentication()).isNull();
  }

  @Test
  void 저장소_장애에는_의도한_오류_code를_요청_속성으로_남긴다() throws ServletException, IOException {
    this.verifier.willReturn(new AccessTokenVerification.Verified(USER_ID, UserRole.LEARNER, 0));
    this.userRepository.willFail();
    HttpServletRequest request = requestWithBearer();

    this.filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(request.getAttribute(ErrorCodeAttribute.class.getName()))
        .isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  void 저장소_조회는_요청당_한_번만_한다() throws ServletException, IOException {
    authenticatedUserExists();
    this.userRepository.resetFindByIdCallCount();

    this.filter.doFilter(requestWithBearer(), new MockHttpServletResponse(), new MockFilterChain());

    assertThat(this.userRepository.findByIdCallCount()).isEqualTo(1);
  }
}
