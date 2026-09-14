package com.itda.user.repository;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 계정 작업 토큰 상태 저장소 bean 구성.
 *
 * <p>Redis Lua 스크립트와 {@link ActionTokenStore}만 조립한다. action ring과 발급기·검증기는 {@code user.service}가
 * 조립한다.
 */
@Configuration(proxyBeanMethods = false)
public class ActionTokenRepositoryConfiguration {

  @Bean
  public RedisScript<String> issueActionTokenScript() {
    return script("issue-action-token.lua");
  }

  @Bean
  public RedisScript<String> consumeActionTokenScript() {
    return script("consume-action-token.lua");
  }

  @Bean
  public ActionTokenStore actionTokenStore(
      StringRedisTemplate stringRedisTemplate,
      AuthRedisKeys authRedisKeys,
      RedisScript<String> issueActionTokenScript,
      RedisScript<String> consumeActionTokenScript) {
    return new LuaActionTokenStore(
        stringRedisTemplate,
        authRedisKeys,
        issueActionTokenScript,
        consumeActionTokenScript,
        Clock.systemUTC());
  }

  private static RedisScript<String> script(String fileName) {
    return RedisScript.of(new ClassPathResource("redis/" + fileName), String.class);
  }
}
