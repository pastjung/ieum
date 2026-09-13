package com.itda.global.security;

import static org.springframework.security.config.Customizer.withDefaults;

import com.itda.user.application.AccessAuthenticationService;
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
 * <p>CSRF는 끈다. 현재 자격 증명 전달 경로가 Authorization 헤더뿐이라 브라우저가 자동으로 실어 보내는 값이 없기 때문이다. <b>WEB Refresh
 * Token을 {@code __Host-refreshToken} 쿠키로 받는 endpoint가 추가되면 그 경로의 CSRF·Origin 정책을 다시 판단해야 한다</b> —
 * 쿠키는 브라우저가 자동으로 붙이므로 지금의 판단 근거가 그대로 성립하지 않는다.
 *
 * <p>CORS는 기존 {@code WebConfig}의 MVC 설정을 그대로 재사용한다. Security chain이 DispatcherServlet보다 앞에 있어 여기서
 * CORS를 켜 두지 않으면 preflight가 먼저 막힌다. 허용 Origin 목록은 이 이슈에서 바꾸지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

  /** 유일한 보호 대상 (API 명세 3.2절). 추가할 때는 이 목록이 아니라 계약 문서가 먼저 움직인다. */
  private static final String ME_PATH = "/users/me";

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
      AccessAuthenticationService accessAuthenticationService,
      AccessAuthenticationEntryPoint accessAuthenticationEntryPoint)
      throws Exception {
    return baseline(http)
        .securityMatcher(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, ME_PATH))
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
  public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
    return baseline(http)
        .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .build();
  }

  private static HttpSecurity baseline(HttpSecurity http) throws Exception {
    return http.cors(withDefaults())
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(formLogin -> formLogin.disable())
        .httpBasic(httpBasic -> httpBasic.disable())
        .logout(logout -> logout.disable());
  }

  /** 보호 chain을 구성하는 후속 이슈가 공유한다. 실패 응답 형식을 한 곳에 묶어 두기 위해 여기서 만든다. */
  @Bean
  public AccessAuthenticationEntryPoint accessAuthenticationEntryPoint() {
    return new AccessAuthenticationEntryPoint();
  }
}
