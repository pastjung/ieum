package com.itda.user.service;

import com.itda.global.security.AccessJwtProperties;
import com.itda.global.security.AccessKeyRing;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Access JWT 발급 bean 구성.
 *
 * <p>key ring과 issuer는 {@code global.security}의 {@code AccessJwtConfiguration}이 등록한 것을 그대로 쓴다. 발급과
 * 검증이 같은 ring·issuer를 쓰는 것이 계약이기 때문이다.
 */
@Configuration(proxyBeanMethods = false)
public class AccessTokenIssuerConfiguration {

  @Bean
  public NimbusAccessTokenIssuer nimbusAccessTokenIssuer(
      AccessKeyRing accessKeyRing, AccessJwtProperties properties) {
    return new NimbusAccessTokenIssuer(accessKeyRing, properties.issuer(), Clock.systemUTC());
  }
}
