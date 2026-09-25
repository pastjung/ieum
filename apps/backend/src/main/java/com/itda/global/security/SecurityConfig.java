package com.itda.global.security;

import com.itda.user.service.AccessAuthenticationService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.filter.CorsFilter;

/**
 * Access Token 기반 보호의 공통 설정.
 *
 * <p><b>chain을 둘로 나눈다.</b> 앞 chain은 보호가 필요한 정확한 method·path에만 걸리고 {@link
 * AccessTokenAuthenticationFilter}도 거기에만 들어간다. 뒤 chain은 나머지 전부를 열어 둔다.
 *
 * <p>{@code anyRequest().authenticated()}로 전체를 막지 않는 이유가 있다. 그렇게 하면 존재하지 않는 경로가 Issue #4가 확정한
 * {@code 404 ENDPOINT_NOT_FOUND} 대신 401이 되고, 공개 API가 {@code Authorization} 헤더나 인증 저장소 상태에 영향을 받는다.
 * 보호 대상은 이슈마다 자기 경로를 명시적으로 추가한다.
 *
 * <p>세션을 만들지 않는다. 인증 근거는 매 요청의 Access Token이며, 서버가 상태를 들고 있으면 {@code auth_version} 기반 무효화가 우회된다.
 *
 * <p><b>CSRF는 끈 채로 두며 이는 재판단을 마친 결정이다.</b> WEB Refresh Token을 {@code __Host-refreshToken} 쿠키로 받는
 * endpoint가 실제로 추가됐다 — {@code POST /access-tokens}(갱신)와 {@code POST
 * /auth-session-revocations}(로그아웃)다. 쿠키는 브라우저가 자동으로 붙이므로 "자격 증명이 Authorization 헤더뿐"이라던 이전 근거는 더 이상
 * 성립하지 않는다. 별도 Spring CSRF token을 도입하는 대신 다음 조합을 방어로 채택했다.
 *
 * <ul>
 *   <li>{@code SameSite=Strict} — 쿠키가 유일한 자격 증명인데 브라우저가 cross-site 요청에는 이 쿠키를 <b>싣지 않는다</b>. 위조 요청은
 *       토큰 없는 요청이 되어 {@code 401}로 끝난다
 *   <li>{@code __Host-} 접두사 — {@code Secure}·{@code Path=/}·{@code Domain} 생략을 브라우저가 강제해 하위 도메인에서
 *       쿠키를 덮어쓸 수 없다
 *   <li>필수 커스텀 헤더 {@code X-Client-Platform} — 두 endpoint가 이 헤더를 요구하므로 요청이 simple request가 될 수 없고
 *       반드시 preflight를 거친다
 *   <li>기존 {@code WebConfig}의 정확한 Origin 허용 목록 — 목록에 없는 Origin은 그 preflight에서 막힌다
 * </ul>
 *
 * <p>Origin 허용 목록을 업무 필터에서 다시 관리하지 않는다. 목록이 두 곳으로 갈라지면 한쪽만 바뀌는 사고가 난다.
 *
 * <p><b>다음 중 하나라도 바뀌면 이 판단을 다시 받아야 한다</b> — cross-site 배포, {@code SameSite}를 {@code None}으로 변경, 필수
 * 커스텀 헤더 제거, CORS 허용 Origin·헤더 정책 완화.
 *
 * <p>CORS는 기존 {@code WebConfig}의 MVC 설정을 그대로 재사용한다. Security chain이 DispatcherServlet보다 앞에 있어 여기서
 * CORS를 켜 두지 않으면 preflight가 먼저 막힌다. 허용 Origin 목록은 이 이슈에서 바꾸지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

  /** 보호 대상 (API 명세 3.2·3.2.1절). 추가할 때는 이 목록이 아니라 계약 문서가 먼저 움직인다. */
  private static final String ME_PATH = "/users/me";

  private static final String WITHDRAWALS_PATH = "/users/me/withdrawals";

  /** 학습 단계 설정. 내 정보를 바꾸므로 당연히 보호 대상이다 */
  private static final String ME_LEVEL_PATH = "/users/me/level";

  /**
   * 학습 세션 경로. 모두 본인 세션만 다루므로 인증이 필요하다.
   *
   * <p>{@code context-path}가 {@code /api/v1}을 붙이므로 여기에는 그 뒤만 적는다.
   */
  private static final String LEARNING_SESSIONS_PATH = "/learning-sessions";

  private static final String LEARNING_SESSION_PATH = "/learning-sessions/{sessionId}";

  private static final String LEARNING_SESSION_COMPLETION_PATH =
      "/learning-sessions/{sessionId}/completion";

  /**
   * 문항 하나의 제출·분석. 다른 학습 세션 경로와 같은 이유로 보호 대상이다.
   *
   * <p>이 경로가 목록에서 빠져 있었다. 그러면 요청이 permitAll chain으로 떨어져 {@link AccessTokenAuthenticationFilter}를
   * 거치지 않고, {@code @AuthenticationPrincipal}이 null인 채로 서비스까지 들어가 401이 아니라 500이 된다 — 유효한 토큰을 들고도 매
   * 문항 제출이 실패한다.
   */
  private static final String LEARNING_SESSION_ATTEMPT_ANALYSIS_PATH =
      "/learning-sessions/{sessionId}/attempt-analysis";

  /**
   * 복습 세션. 학습 세션과 같은 이유로 보호 대상이다 — 둘 다 "이 사용자의" 기록으로 문항을 고른다.
   *
   * <p>이 두 경로도 바로 위 문항 분석과 똑같이 목록에서 빠져 있었다. 그래서 {@code POST}는 500,
   * {@code GET}은 404로 답했다. 학습 세션 경로를 추가할 때 같은 컨트롤러의 복습 경로가 함께 딸려오지
   * 않았기 때문이다 — 경로를 늘릴 때는 그 컨트롤러의 다른 method도 함께 본다.
   */
  /** 진행 중인 세션 조회. 내 학습 이력이므로 보호 대상이다 */
  private static final String IN_PROGRESS_SESSION_PATH = "/learning-sessions/in-progress";

  private static final String REVIEW_SESSIONS_PATH = "/review-sessions";

  private static final String REVIEW_SESSION_PATH = "/review-sessions/{sessionId}";

  /**
   * CORS 설정 원본인 MVC 설정을 읽는 bean. Spring Security의 {@code http.cors()}도 MVC가 있으면 이 이름의 bean을 {@link
   * CorsConfigurationSource}로 찾는다.
   *
   * <p>구현 타입({@code HandlerMappingIntrospector})으로 받지 않는다. Spring 7에서 제거 예정으로 표시돼 있고 여기서 필요한 것은 설정
   * 조회 역할뿐이다.
   */
  private static final String MVC_CORS_CONFIGURATION_SOURCE = "mvcHandlerMappingIntrospector";

  /**
   * Access Token으로 보호하는 경로.
   *
   * <p>{@code securityMatcher}에 <b>method까지</b> 넣는다. 경로만 지정하면 같은 경로의 다른 method가 이 chain에 들어와, 계약상
   * {@code 405}여야 할 요청이 401이 된다.
   *
   * <p>{@link AccessTokenAuthenticationFilter}를 bean으로 두지 않고 여기서 만들어 끼운다. Filter 타입 bean은 Spring
   * Boot가 servlet chain 전체에 자동 등록하므로, 그러면 공개 경로와 존재하지 않는 경로까지 Authorization 헤더를 해석하게 된다.
   */
  @Bean
  @Order(1)
  public SecurityFilterChain protectedApiSecurityFilterChain(
      HttpSecurity http,
      @Qualifier(MVC_CORS_CONFIGURATION_SOURCE) CorsConfigurationSource corsConfigurationSource,
      CorsProcessor corsProcessor,
      AccessAuthenticationService accessAuthenticationService,
      AccessAuthenticationEntryPoint accessAuthenticationEntryPoint)
      throws Exception {
    return baseline(http, corsConfigurationSource, corsProcessor)
        .securityMatcher(
            new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, ME_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, WITHDRAWALS_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.PUT, ME_LEVEL_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LEARNING_SESSIONS_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, LEARNING_SESSION_PATH),
                PathPatternRequestMatcher.pathPattern(
                    HttpMethod.POST, LEARNING_SESSION_COMPLETION_PATH),
                PathPatternRequestMatcher.pathPattern(
                    HttpMethod.POST, LEARNING_SESSION_ATTEMPT_ANALYSIS_PATH),
                PathPatternRequestMatcher.pathPattern(
                    HttpMethod.GET, IN_PROGRESS_SESSION_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, REVIEW_SESSIONS_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, REVIEW_SESSION_PATH)))
        .addFilterBefore(
            new AccessTokenAuthenticationFilter(accessAuthenticationService),
            AuthorizationFilter.class)
        .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
        // 토큰 없음과 토큰 불량이 같은 401이어야 하므로 응답을 한 곳에서 만든다.
        .exceptionHandling(
            handling -> handling.authenticationEntryPoint(accessAuthenticationEntryPoint))
        .build();
  }

  /** 나머지 전부. 공개 API·{@code /health}·존재하지 않는 경로가 여기로 온다. */
  @Bean
  @Order(2)
  public SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http,
      @Qualifier(MVC_CORS_CONFIGURATION_SOURCE) CorsConfigurationSource corsConfigurationSource,
      CorsProcessor corsProcessor)
      throws Exception {
    return baseline(http, corsConfigurationSource, corsProcessor)
        .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .build();
  }

  private static HttpSecurity baseline(
      HttpSecurity http,
      CorsConfigurationSource corsConfigurationSource,
      CorsProcessor corsProcessor)
      throws Exception {
    return http.addFilter(corsFilter(corsConfigurationSource, corsProcessor))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(formLogin -> formLogin.disable())
        .httpBasic(httpBasic -> httpBasic.disable())
        .logout(logout -> logout.disable());
  }

  /**
   * {@code http.cors()}가 만드는 것과 같은 설정 원본·같은 위치의 {@link CorsFilter}에 거부 응답 processor만 바꿔 끼운다.
   *
   * <p>{@code http.cors()}는 filter를 직접 만들어 등록해 processor를 지정할 방법이 없다. {@link CorsFilter}를 bean으로 두는
   * 방법은 쓰지 않는다 — Filter 타입 bean은 Spring Boot가 servlet chain 전체에도 자동 등록한다. 허용 Origin·method·header
   * 정책은 여전히 {@code WebConfig}의 MVC 설정 한 곳에만 있다.
   */
  private static CorsFilter corsFilter(
      CorsConfigurationSource corsConfigurationSource, CorsProcessor corsProcessor) {
    CorsFilter corsFilter = new CorsFilter(corsConfigurationSource);
    corsFilter.setCorsProcessor(corsProcessor);
    return corsFilter;
  }

  /** 보호 chain을 구성하는 후속 이슈가 공유한다. 실패 응답 형식을 한 곳에 묶어 두기 위해 여기서 만든다. */
  @Bean
  public AccessAuthenticationEntryPoint accessAuthenticationEntryPoint() {
    return new AccessAuthenticationEntryPoint();
  }
}
