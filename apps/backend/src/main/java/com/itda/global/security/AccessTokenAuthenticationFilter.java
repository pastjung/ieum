package com.itda.global.security;

import com.itda.global.error.ErrorCode;
import com.itda.global.error.ErrorCodeAttribute;
import com.itda.user.service.AccessAuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer Access Token으로 요청을 인증한다 (API 명세 3.1·3.6.1절).
 *
 * <p><b>bean으로 등록하지 않는다.</b> Filter 타입 bean은 Spring Boot가 servlet chain 전체에 자동 등록하므로, 그렇게 두면 공개 경로와
 * 존재하지 않는 경로까지 Authorization 헤더를 해석하게 되어 기존 200·404 계약이 401로 바뀐다. 보호가 필요한 {@code
 * SecurityFilterChain}이 직접 생성해 끼운다.
 *
 * <p>인증에 실패해도 여기서 401을 만들지 않고 인증되지 않은 상태로 통과시킨다. 실제 401은 {@code AuthorizationFilter}가 판단하고 {@link
 * AccessAuthenticationEntryPoint}가 한 곳에서 만든다 — 토큰 없음과 토큰 불량이 같은 응답이어야 하기 때문이다.
 *
 * <p>저장소 상태를 확인하지 못한 경우만 예외다. 이때는 인증 실패로 위장하지 않고 {@code 503 AUTH_STATE_UNAVAILABLE}로 끊는다 (API 명세
 * 3.6.2절 fail-closed).
 *
 * <p>ERROR dispatch에서는 동작하지 않는다({@code OncePerRequestFilter} 기본값). 이미 끝난 오류 응답을 다시 인증 판정할 이유가 없다.
 */
public class AccessTokenAuthenticationFilter extends OncePerRequestFilter {

  private static final String ROLE_PREFIX = "ROLE_";

  private final AccessAuthenticationService accessAuthenticationService;
  private final SecurityContextHolderStrategy securityContextHolderStrategy =
      SecurityContextHolder.getContextHolderStrategy();

  public AccessTokenAuthenticationFilter(AccessAuthenticationService accessAuthenticationService) {
    this.accessAuthenticationService = accessAuthenticationService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<String> token = BearerTokenExtractor.extract(request);
    if (token.isEmpty()) {
      chain.doFilter(request, response);
      return;
    }
    AccessAuthenticationService.Result result =
        this.accessAuthenticationService.authenticate(token.get());
    switch (result) {
      case AccessAuthenticationService.Result.Authenticated authenticated ->
          authenticateAndContinue(authenticated, request, response, chain);
      case AccessAuthenticationService.Result.Rejected ignored -> chain.doFilter(request, response);
      case AccessAuthenticationService.Result.Unavailable ignored ->
          sendAuthStateUnavailable(request, response);
    }
  }

  private void authenticateAndContinue(
      AccessAuthenticationService.Result.Authenticated authenticated,
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain chain)
      throws ServletException, IOException {
    SecurityContext context = this.securityContextHolderStrategy.createEmptyContext();
    // credentials는 null이다. 토큰 원문을 Authentication에 보관하면 요청이 끝날 때까지 메모리와 로그에 남는다.
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new AuthenticatedUser(authenticated.userId(), authenticated.role()),
            null,
            List.of(new SimpleGrantedAuthority(ROLE_PREFIX + authenticated.role().name()))));
    this.securityContextHolderStrategy.setContext(context);
    try {
      chain.doFilter(request, response);
    } finally {
      // 스레드 풀이 재사용되므로 다음 요청으로 새지 않게 직접 비운다.
      this.securityContextHolderStrategy.clearContext();
    }
  }

  private static void sendAuthStateUnavailable(
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    ErrorCodeAttribute.set(request, ErrorCode.AUTH_STATE_UNAVAILABLE);
    // message를 넘기지 않는다. 컨테이너가 그 문자열을 노출할 수 있다.
    response.sendError(ErrorCode.AUTH_STATE_UNAVAILABLE.getStatus().value());
  }
}
