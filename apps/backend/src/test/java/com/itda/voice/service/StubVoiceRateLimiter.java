package com.itda.voice.service;

import com.itda.voice.repository.VoiceRateLimiter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 허용·거절·장애를 스위치로 고르는 사용량 제한기 대역. 실제 Redis 카운터는 RedisVoiceRateLimiterRedisTest가 검증한다. */
public class StubVoiceRateLimiter implements VoiceRateLimiter {

  public volatile boolean allowing = true;

  /** null이 아니면 이 예외를 던진다 — 저장소 장애 흉내. */
  public volatile RuntimeException failure;

  public final List<Long> acquiredBy = new CopyOnWriteArrayList<>();

  public void reset() {
    this.allowing = true;
    this.failure = null;
    this.acquiredBy.clear();
  }

  @Override
  public boolean tryAcquire(long userId) {
    RuntimeException toThrow = this.failure;
    if (toThrow != null) {
      throw toThrow;
    }
    this.acquiredBy.add(userId);
    return this.allowing;
  }
}
