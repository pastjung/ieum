package com.itda.voice.repository;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 학습자별 UTC 분 단위 카운터. 키 {@code voice:rate:{userId}:{epochMinute}}, TTL 120초 (API 명세 12절).
 *
 * <p>TTL을 한 분보다 길게 두는 이유: 분이 바뀌는 순간에 도착한 증가가 이미 지워진 키를 되살려 TTL 없이 남지 않게 여유를 준다. 지난 분의 키는 더 이상 읽지
 * 않으므로 늦게 지워져도 결과가 바뀌지 않는다.
 *
 * <p>인증 상태 키와 달리 환경 prefix를 붙이지 않는다 — 키 모양은 교차 계약이 정했고, 운영 Redis는 이 서비스 전용이다.
 */
@Component
public class RedisVoiceRateLimiter implements VoiceRateLimiter {

  static final int LIMIT_PER_MINUTE = 30;
  static final Duration KEY_TTL = Duration.ofSeconds(120);

  /** bean으로 두지 않는다. 기존 {@code RedisScript<String>} bean들이 파라미터 이름 주입에 기대고 있다. */
  private static final RedisScript<Long> INCREMENT_SCRIPT =
      RedisScript.of(new ClassPathResource("redis/increment-voice-rate.lua"), Long.class);

  private final StringRedisTemplate redis;
  private final Clock clock;

  @Autowired
  public RedisVoiceRateLimiter(StringRedisTemplate redis) {
    this(redis, Clock.systemUTC());
  }

  RedisVoiceRateLimiter(StringRedisTemplate redis, Clock clock) {
    this.redis = redis;
    this.clock = clock;
  }

  @Override
  public boolean tryAcquire(long userId) {
    long epochMinute = Math.floorDiv(this.clock.instant().getEpochSecond(), 60);
    Long count =
        this.redis.execute(
            INCREMENT_SCRIPT,
            List.of(key(userId, epochMinute)),
            Long.toString(KEY_TTL.toSeconds()));
    return count != null && count <= LIMIT_PER_MINUTE;
  }

  static String key(long userId, long epochMinute) {
    return "voice:rate:" + userId + ":" + epochMinute;
  }
}
