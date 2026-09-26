package com.itda.user.service;

import com.itda.global.security.AccessTokenVerifier;
import com.itda.user.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 보호 API의 사용자 인증 상태 판정 bean 구성.
 *
 * <p>Service 클래스에는 Spring 애너테이션을 붙이지 않고 여기서 조립한다 — 유스케이스는 프레임워크 없이도 생성·테스트할 수 있어야 한다.
 */
@Configuration(proxyBeanMethods = false)
public class AccessAuthenticationConfiguration {

  @Bean
  public AccessAuthenticationService accessAuthenticationService(
      AccessTokenVerifier accessTokenVerifier, UserRepository userRepository) {
    return new AccessAuthenticationService(accessTokenVerifier, userRepository);
  }
}
