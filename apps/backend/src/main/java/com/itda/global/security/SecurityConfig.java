package com.itda.global.security;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Access Token 기반 보호의 공통 설정.
 *
 * <p><b>운영 chain은 아직 아무 경로도 보호하지 않는다.</b> 현재 운영 endpoint는 {@code /health} 하나뿐이고, 보호 대상 업무 API는 후속
 * 이슈에서 들어온다. 여기서 {@code anyRequest().authenticated()}로 막으면 존재하지 않는 경로가 Issue #4가 확정한 {@code 404
 * ENDPOINT_NOT_FOUND} 대신 401이 되어 기존 계약이 깨진다. 보호 matcher와 {@link AccessTokenAuthenticationFilter}
 * 등록은 각 업무 API 구현 이슈가 자기 경로에 대해 명시적으로 추가한다.
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

  @Bean
  public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.cors(withDefaults())
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(formLogin -> formLogin.disable())
        .httpBasic(httpBasic -> httpBasic.disable())
        .logout(logout -> logout.disable())
        .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .build();
  }

  /** 보호 chain을 구성하는 후속 이슈가 공유한다. 실패 응답 형식을 한 곳에 묶어 두기 위해 여기서 만든다. */
  @Bean
  public AccessAuthenticationEntryPoint accessAuthenticationEntryPoint() {
    return new AccessAuthenticationEntryPoint();
  }
}
