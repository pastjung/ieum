package com.itda.user.repository;

import com.itda.global.config.RuntimeEnvironmentProfile;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Refresh Token 상태 저장소 bean 구성.
 *
 * <p>Lua 스크립트는 {@code .lua} 파일로 두고 {@link RedisScript}가 읽는다. Java 문자열에 넣으면 줄바꿈과 이스케이프가 섞여 스크립트를 눈으로
 * 검토하기 어렵고, Redis 쪽 문법 검사를 받을 방법도 없다.
 *
 * <p>{@code RedisScript} bean은 싱글턴이다. 인스턴스마다 SHA1을 한 번만 계산하고 {@code EVALSHA}를 재사용하기 위해서다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthRedisProperties.class)
public class RefreshTokenRedisConfiguration {

  /**
   * 키 prefix가 실행 프로필과 어긋나면 기동에 실패한다.
   *
   * <p>prefix는 환경을 가르는 유일한 수단이다 (DB index를 쓰지 않는 이유는 {@link AuthRedisProperties} 주석 참고). 값이 하나만 틀려도
   * test로 뜬 서버가 운영 토큰 상태를 읽고 쓰게 되는데, 그때는 오류가 아니라 "다른 사람의 세션"으로 조용히 동작한다.
   *
   * <p>실행 환경 판정은 {@link RuntimeEnvironmentProfile#resolve(Environment)}를 그대로 쓴다 — 보조 프로필 무시, 둘 이상이면
   * 실패, 하나로 결정되지 않으면 실패라는 규칙이 issuer 검증과 같아야 한다.
   */
  @Bean
  public AuthRedisKeys authRedisKeys(AuthRedisProperties properties, Environment environment) {
    requireKeyPrefixMatchesProfile(properties.keyPrefix(), environment);
    return new AuthRedisKeys(properties);
  }

  /** message에 실제 prefix·환경변수 값·Redis 주소를 넣지 않는다. */
  static void requireKeyPrefixMatchesProfile(String keyPrefix, Environment environment) {
    if (!RuntimeEnvironmentProfile.resolve(environment).equals(keyPrefix)) {
      throw new IllegalStateException("itda.security.auth-redis.key-prefix가 현재 실행 프로필과 일치하지 않습니다.");
    }
  }

  @Bean
  public RedisScript<String> issueRefreshFamilyScript() {
    return script("issue-refresh-family.lua");
  }

  @Bean
  public RedisScript<String> rotateRefreshScript() {
    return script("rotate-refresh.lua");
  }

  @Bean
  public RedisScript<String> revokeFamilyScript() {
    return script("revoke-family.lua");
  }

  @Bean
  public RefreshTokenStore refreshTokenStore(
      StringRedisTemplate stringRedisTemplate,
      AuthRedisKeys authRedisKeys,
      RedisScript<String> issueRefreshFamilyScript,
      RedisScript<String> rotateRefreshScript,
      RedisScript<String> revokeFamilyScript) {
    return new LuaRefreshTokenStore(
        stringRedisTemplate,
        authRedisKeys,
        issueRefreshFamilyScript,
        rotateRefreshScript,
        revokeFamilyScript);
  }

  private static RedisScript<String> script(String fileName) {
    return RedisScript.of(new ClassPathResource("redis/" + fileName), String.class);
  }
}
