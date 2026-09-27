package com.itda.voice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.redis.RequiresRealRedis;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 음성 사용량 카운터를 실제 Redis에 대고 검증한다 (API 명세 12절). INCR과 첫 증가 때의 EXPIRE가 한 Lua 호출로 묶였는지, 키 모양과 TTL이 계약과
 * 같은지는 가짜 구현으로는 증명되지 않는다.
 *
 * <p>FLUSHDB를 쓰지 않는다. 이 테스트가 만든 키만 지운다.
 */
@SpringBootTest
@RequiresRealRedis
class RedisVoiceRateLimiterRedisTest {

  /** 다른 Redis 테스트(8_000_000_000·9_000_000_000·9_100_000_000·9_200_000_000 대역)와 겹치지 않는 대역. */
  private static final AtomicLong USER_ID_SEQUENCE = new AtomicLong(9_500_000_000L);

  /** 분의 중간 시각. 테스트 도중 분이 바뀌어 흔들리지 않게 시계를 고정한다. */
  private static final Instant NOW = Instant.parse("2026-09-27T12:00:30Z");

  @Autowired private StringRedisTemplate redis;

  private long userId;
  private final List<String> createdKeys = new ArrayList<>();

  @BeforeEach
  void setUp() {
    this.userId = USER_ID_SEQUENCE.incrementAndGet();
  }

  @AfterEach
  void deleteOnlyKeysCreatedHere() {
    this.redis.delete(this.createdKeys);
  }

  private RedisVoiceRateLimiter limiterAt(Instant instant, long forUser) {
    String key = RedisVoiceRateLimiter.key(forUser, instant.getEpochSecond() / 60);
    this.createdKeys.add(key);
    this.redis.delete(key); // 앞선 실행이 남긴 키가 있으면 지우고 시작한다
    return new RedisVoiceRateLimiter(this.redis, Clock.fixed(instant, ZoneOffset.UTC));
  }

  @Test
  void 같은_분에는_30회까지_허용하고_31번째를_거절한다() {
    RedisVoiceRateLimiter limiter = limiterAt(NOW, this.userId);

    for (int call = 1; call <= RedisVoiceRateLimiter.LIMIT_PER_MINUTE; call++) {
      assertThat(limiter.tryAcquire(this.userId)).as("%d번째 호출", call).isTrue();
    }
    assertThat(limiter.tryAcquire(this.userId)).isFalse();
  }

  @Test
  void 카운터_키는_계약_모양이고_TTL은_120초_이하로_붙는다() {
    RedisVoiceRateLimiter limiter = limiterAt(NOW, this.userId);

    limiter.tryAcquire(this.userId);
    limiter.tryAcquire(this.userId);

    String key = "voice:rate:" + this.userId + ":" + (NOW.getEpochSecond() / 60);
    assertThat(this.redis.opsForValue().get(key)).isEqualTo("2");
    assertThat(this.redis.getExpire(key)).isBetween(1L, 120L);
  }

  @Test
  void 다음_분에는_새로_센다() {
    RedisVoiceRateLimiter thisMinute = limiterAt(NOW, this.userId);
    for (int call = 0; call < RedisVoiceRateLimiter.LIMIT_PER_MINUTE; call++) {
      thisMinute.tryAcquire(this.userId);
    }
    assertThat(thisMinute.tryAcquire(this.userId)).isFalse();

    RedisVoiceRateLimiter nextMinute = limiterAt(NOW.plusSeconds(60), this.userId);

    assertThat(nextMinute.tryAcquire(this.userId)).isTrue();
  }

  @Test
  void 학습자마다_따로_센다() {
    long otherUserId = USER_ID_SEQUENCE.incrementAndGet();
    RedisVoiceRateLimiter limiter = limiterAt(NOW, this.userId);
    limiterAt(NOW, otherUserId);
    for (int call = 0; call <= RedisVoiceRateLimiter.LIMIT_PER_MINUTE; call++) {
      limiter.tryAcquire(this.userId);
    }

    assertThat(limiter.tryAcquire(otherUserId)).isTrue();
  }
}
