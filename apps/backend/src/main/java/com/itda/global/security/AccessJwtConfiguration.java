package com.itda.global.security;

import com.itda.global.config.RuntimeEnvironmentProfile;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Access JWT 설정과 검증 bean 구성.
 *
 * <p>설정 properties 등록을 이 패키지 안에 두어 전역 진입점({@code ItdaApplication})을 건드리지 않는다.
 *
 * <p>발급기는 {@code user.service}의 {@code AccessTokenIssuerConfiguration}이 여기서 등록한 key ring과 issuer로
 * 만든다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccessJwtProperties.class)
public class AccessJwtConfiguration {

  private static final String ISSUER_PREFIX = "itda-backend-";

  /**
   * key ring은 기동 시점에 만든다. 형식·길이·중복 오류를 첫 인증 요청이 아니라 기동에서 드러내기 위해서다.
   *
   * <p>issuer가 현재 프로필과 어긋나면 함께 실패시킨다. local 설정으로 뜬 서버가 prod issuer를 서명해 넣는 사고를 막는다.
   */
  @Bean
  public AccessKeyRing accessKeyRing(AccessJwtProperties properties, Environment environment) {
    requireIssuerMatchesProfile(properties.issuer(), environment);
    return AccessKeyRing.from(properties);
  }

  @Bean
  public NimbusAccessTokenVerifier nimbusAccessTokenVerifier(
      AccessKeyRing accessKeyRing, AccessJwtProperties properties) {
    return new NimbusAccessTokenVerifier(accessKeyRing, properties.issuer(), Clock.systemUTC());
  }

  /** message에 설정된 issuer나 key를 넣지 않는다 — 설정 오류 로그가 그 값을 옮겨 적을 이유가 없다. */
  static void requireIssuerMatchesProfile(String issuer, Environment environment) {
    if (!(ISSUER_PREFIX + RuntimeEnvironmentProfile.resolve(environment)).equals(issuer)) {
      throw new IllegalStateException("itda.security.jwt.access.issuer가 현재 실행 프로필과 일치하지 않습니다.");
    }
  }
}
