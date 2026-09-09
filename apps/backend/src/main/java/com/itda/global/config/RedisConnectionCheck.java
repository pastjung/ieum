package com.itda.global.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * 로컬 개발에서 Redis 연결을 기동 시점에 한 번 증명한다.
 *
 * <p><b>왜 필요한가</b>: Lettuce는 지연 연결이라 host·port가 틀려도 애플리케이션이 오류 없이 정상 기동한다. MariaDB는 ddl-auto가 기동 중
 * 커넥션을 열어 연결을 자동으로 증명하지만 Redis는 그런 경로가 없다. 인증 토큰 상태 저장(docs/product/erd.md 3.1.1)이 아직 구현 전이라 Redis에
 * 명령을 보내는 코드가 하나도 없어, 설정이 맞는지 확인할 방법이 없다.
 *
 * <p><b>임시 코드다</b>: 토큰 저장소가 들어오면 그 통합 테스트가 이 역할을 대신하므로 이 클래스를 삭제한다.
 *
 * <p>연결 실패 시 예외를 삼키지 않는다 — 조용히 잘못된 설정으로 개발하는 것이 이 클래스가 막으려는 상황이다.
 */
@Profile("local")
@Component
public class RedisConnectionCheck implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(RedisConnectionCheck.class);

  private final RedisConnectionFactory connectionFactory;

  public RedisConnectionCheck(RedisConnectionFactory connectionFactory) {
    this.connectionFactory = connectionFactory;
  }

  @Override
  public void run(ApplicationArguments args) {
    try (RedisConnection connection = connectionFactory.getConnection()) {
      log.info("Redis 연결 확인: {}", connection.ping());
    }
  }
}
