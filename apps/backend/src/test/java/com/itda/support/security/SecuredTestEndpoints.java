package com.itda.support.security;

import com.itda.global.security.AccessAuthenticationEntryPoint;
import com.itda.global.security.AccessTokenAuthenticationFilter;
import com.itda.global.security.AuthenticatedUser;
import com.itda.user.application.AccessAuthenticationService;
import com.itda.user.application.port.out.AccessTokenVerifier;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Access 인증을 실제 HTTP로 검증하기 위한 보호 endpoint와 전용 chain. 테스트 소스에만 있고 {@code WebApiTest}가 import할 때만
 * 등록된다.
 *
 * <p>보호 범위를 두 경로로 정확히 한정한다. {@code /test/**} 전체를 막으면 Issue #4가 만든 공통 오류·요청 추적 테스트 endpoint가 전부 401이
 * 되어 그 계약 검증이 사라진다.
 *
 * <p>운영 chain({@code SecurityConfig})은 matcher가 없어 가장 낮은 우선순위로 남고, 여기 두 chain이 먼저 자기 경로만 가져간다.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(SecuredTestEndpoints.SecuredController.class)
public class SecuredTestEndpoints {

  public static final String SECURED_PATH = "/test/secured";
  public static final String UNAVAILABLE_PATH = "/test/secured-unavailable";
  public static final String CURRENT_AUTHENTICATION_PATH = "/test/current-authentication";
  public static final String UNAVAILABLE_LOOKUP_COUNT_PATH = "/test/secured-unavailable-lookups";

  /** 정상 인증 경로. 실제 저장소를 그대로 쓴다. */
  @Bean
  @Order(1)
  SecurityFilterChain securedTestChain(
      HttpSecurity http,
      AccessAuthenticationService accessAuthenticationService,
      AccessAuthenticationEntryPoint entryPoint)
      throws Exception {
    return protect(http, SECURED_PATH, accessAuthenticationService, entryPoint);
  }

  /**
   * 저장소 장애 경로. 실제 Repository bean을 망가뜨리는 대신 이 chain에서만 쓰는 서비스를 따로 조립한다 — 다른 테스트의 저장소 동작을 건드리지 않기
   * 위해서다.
   */
  /**
   * 장애를 흉내 내는 저장소를 {@code UserRepository} 타입 bean으로 노출하지 않는다. 운영 어댑터와 주입 후보가 겹쳐 컨텍스트가 깨진다. 그래서 감싼
   * 타입으로만 등록한다.
   */
  @Bean
  FailingUserRepositoryHolder failingUserRepositoryHolder() {
    return new FailingUserRepositoryHolder();
  }

  public static final class FailingUserRepositoryHolder {

    private final StubUserRepository repository = new StubUserRepository();

    FailingUserRepositoryHolder() {
      this.repository.willFail();
    }

    public StubUserRepository repository() {
      return this.repository;
    }
  }

  @Bean
  @Order(2)
  SecurityFilterChain unavailableTestChain(
      HttpSecurity http,
      AccessTokenVerifier accessTokenVerifier,
      FailingUserRepositoryHolder failingUserRepositoryHolder,
      AccessAuthenticationEntryPoint entryPoint)
      throws Exception {
    return protect(
        http,
        UNAVAILABLE_PATH,
        new AccessAuthenticationService(
            accessTokenVerifier, failingUserRepositoryHolder.repository()),
        entryPoint);
  }

  private static SecurityFilterChain protect(
      HttpSecurity http,
      String path,
      AccessAuthenticationService accessAuthenticationService,
      AccessAuthenticationEntryPoint entryPoint)
      throws Exception {
    return http.securityMatcher(path)
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint))
        .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
        .addFilterBefore(
            new AccessTokenAuthenticationFilter(accessAuthenticationService),
            AuthorizationFilter.class)
        .build();
  }

  @RestController
  static class SecuredController {

    private final FailingUserRepositoryHolder failingUserRepositoryHolder;

    SecuredController(FailingUserRepositoryHolder failingUserRepositoryHolder) {
      this.failingUserRepositoryHolder = failingUserRepositoryHolder;
    }

    /** 공개 경로다. 저장소 장애 경로가 요청당 조회를 몇 번 시도했는지 센다. */
    @GetMapping(UNAVAILABLE_LOOKUP_COUNT_PATH)
    Map<String, Object> unavailableLookupCount() {
      return Map.of(
          "findByIdCallCount", this.failingUserRepositoryHolder.repository().findByIdCallCount());
    }

    @GetMapping(SECURED_PATH)
    Map<String, Object> secured() {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("userId", principal.userId());
      body.put("role", principal.role().name());
      body.put("authorities", authentication.getAuthorities().toString());
      body.put("credentialsPresent", authentication.getCredentials() != null);
      body.put("detailsPresent", authentication.getDetails() != null);
      body.put("authenticationToString", authentication.toString());
      return body;
    }

    @GetMapping(UNAVAILABLE_PATH)
    Map<String, Object> unavailable() {
      return Map.of("reached", true);
    }

    /** 공개 경로다. 앞선 요청의 SecurityContext가 스레드에 남았는지 확인한다. */
    @GetMapping(CURRENT_AUTHENTICATION_PATH)
    Map<String, Object> currentAuthentication() {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      return Map.of(
          "authenticated",
          authentication != null && authentication.isAuthenticated(),
          "principal",
          authentication == null ? "none" : String.valueOf(authentication.getPrincipal()));
    }
  }
}
