package com.itda.user.infrastructure;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 상태 Redis key의 환경 분리 설정 (API 명세 3.6.1절 「Redis prefix」).
 *
 * <p>Redis DB index로 환경을 나누지 않는다. DB index는 Cluster에서 쓸 수 없고, 잘못된 설정이 조용히 다른 환경의 데이터를 읽게 만든다. 대신 모든
 * 키 앞에 환경 이름을 붙인다.
 *
 * <p>허용하지 않는 값이나 프로필과 어긋난 값은 기동 단계에서 거부한다.
 */
@ConfigurationProperties("itda.security.auth-redis")
public record AuthRedisProperties(String keyPrefix) {

  public static final Set<String> ALLOWED_PREFIXES = Set.of("local", "test", "prod");

  private static final String PLACEHOLDER_PREFIX = "${";

  public AuthRedisProperties {
    if (keyPrefix == null || keyPrefix.isBlank()) {
      throw new IllegalArgumentException("itda.security.auth-redis.key-prefix가 없습니다.");
    }
    if (keyPrefix.startsWith(PLACEHOLDER_PREFIX)) {
      throw new IllegalArgumentException(
          "itda.security.auth-redis.key-prefix가 해석되지 않은 placeholder입니다. 환경변수를 확인해 주세요.");
    }
    if (!ALLOWED_PREFIXES.contains(keyPrefix)) {
      throw new IllegalArgumentException(
          "itda.security.auth-redis.key-prefix는 local, test, prod 중 하나여야 합니다.");
    }
  }
}
