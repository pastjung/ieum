package com.itda.user.infrastructure;

import com.itda.user.application.AccessAuthenticationService;
import com.itda.user.application.port.out.AccessTokenVerifier;
import com.itda.user.domain.UserRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Access Token 관련 bean 구성.
 *
 * <p>설정 properties 등록을 이 도메인 안에 두어 전역 진입점({@code ItdaApplication})을 건드리지 않는다.
 *
 * <p>application 계층 클래스에는 Spring 애너테이션을 붙이지 않고 여기서 조립한다 — 유스케이스는 프레임워크 없이도 생성·테스트할 수 있어야 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccessJwtProperties.class)
public class AccessTokenConfiguration {

  private static final String ISSUER_PREFIX = "itda-backend-";

  /** 배포 환경을 뜻하는 프로필. 나머지 보조 프로필(디버깅·기능 토글 등)은 실행 환경 판단에 쓰지 않는다. */
  private static final Set<String> ENVIRONMENT_PROFILES = Set.of("local", "test", "prod");

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

  /** 하나의 bean이 발급 port와 검증 port를 모두 만족한다. 같은 ring·issuer를 쓰는 것이 계약이기 때문이다. */
  @Bean
  public NimbusAccessTokenCodec nimbusAccessTokenCodec(
      AccessKeyRing accessKeyRing, AccessJwtProperties properties) {
    return new NimbusAccessTokenCodec(accessKeyRing, properties.issuer(), Clock.systemUTC());
  }

  @Bean
  public AccessAuthenticationService accessAuthenticationService(
      AccessTokenVerifier accessTokenVerifier, UserRepository userRepository) {
    return new AccessAuthenticationService(accessTokenVerifier, userRepository);
  }

  /** message에 설정된 issuer나 key를 넣지 않는다 — 설정 오류 로그가 그 값을 옮겨 적을 이유가 없다. */
  static void requireIssuerMatchesProfile(String issuer, Environment environment) {
    if (!(ISSUER_PREFIX + resolveEnvironmentProfile(environment)).equals(issuer)) {
      throw new IllegalStateException("itda.security.jwt.access.issuer가 현재 실행 프로필과 일치하지 않습니다.");
    }
  }

  /**
   * 실행 환경 프로필을 하나로 결정한다.
   *
   * <p>첫 번째 활성 프로필을 쓰지 않는 이유: 보조 프로필을 함께 켜는 것은 정상적인 사용이고, 그 순서에 따라 issuer 판정이 달라지면 같은 설정이 실행마다 다르게
   * 해석된다. 그래서 배포 환경을 뜻하는 셋 중 몇 개가 켜졌는지로만 판단한다.
   *
   * <p>둘 이상이면 어느 환경인지 단정할 수 없으므로 기동에 실패한다. 잘못 고른 환경으로 토큰을 서명하기 시작하는 것보다 뜨지 않는 편이 낫다.
   */
  static String resolveEnvironmentProfile(Environment environment) {
    List<String> active = environmentProfilesIn(environment.getActiveProfiles());
    if (active.size() > 1) {
      throw new IllegalStateException("실행 환경 프로필(local, test, prod)이 둘 이상 활성화되어 환경을 결정할 수 없습니다.");
    }
    if (active.size() == 1) {
      return active.get(0);
    }
    List<String> defaults = environmentProfilesIn(environment.getDefaultProfiles());
    if (defaults.size() != 1) {
      throw new IllegalStateException("실행 환경 프로필(local, test, prod)을 하나로 결정하지 못했습니다.");
    }
    return defaults.get(0);
  }

  private static List<String> environmentProfilesIn(String[] profiles) {
    return Arrays.stream(profiles).filter(ENVIRONMENT_PROFILES::contains).distinct().toList();
  }
}
