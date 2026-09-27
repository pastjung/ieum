package com.itda.support.redis;

import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.repository.ActionTokenStore;
import com.itda.user.repository.AuthRedisKeys;
import com.itda.user.service.CompactTokenHasher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 실제 Redis를 쓰는 계정 작업 토큰 테스트의 공통 준비.
 *
 * <p><b>FLUSHDB·FLUSHALL을 쓰지 않는다.</b> 개발자의 로컬 Redis에는 다른 데이터가 있고, 테스트가 그것을 지우는 것은 테스트의 권한 밖이다. 대신
 * 실행마다 새 {@code userId}·{@code jti}를 만들어 서로 간섭하지 않게 하고, 정리 단계에서 이 테스트가 만든 키만 골라 지운다.
 */
public abstract class ActionTokenRedisTestBase {

  /** 운영 데이터와 겹칠 수 없는 대역에서 시작한다. Refresh 테스트와도 겹치지 않게 다른 대역을 쓴다. */
  private static final AtomicLong USER_ID_SEQUENCE = new AtomicLong(8_000_000_000L);

  private static final long EMAIL_TTL_SECONDS = 24 * 60 * 60L;

  /** key의 절대 만료 시각을 읽는다. -1은 TTL 없음, -2는 key 없음 (Redis 7.0+). */
  private static final RedisScript<Long> EXPIRE_TIME_SCRIPT =
      RedisScript.of("return redis.call('EXPIRETIME', KEYS[1])", Long.class);

  @Autowired protected ActionTokenStore store;
  @Autowired protected StringRedisTemplate redis;
  @Autowired protected AuthRedisKeys keys;

  protected long userId;

  /** 한 테스트 안에서는 같은 만료 시각을 쓴다. 등록과 소비가 서로 다른 초를 쓰면 저장값과 어긋나 테스트가 흔들린다. */
  protected long expiresAt;

  private final List<String> createdKeys = new ArrayList<>();

  @BeforeEach
  protected void prepareIdentifiers() {
    this.userId = USER_ID_SEQUENCE.incrementAndGet();
    this.expiresAt = Instant.now().getEpochSecond() + EMAIL_TTL_SECONDS;
    for (AccountActionPurpose purpose : AccountActionPurpose.values()) {
      this.createdKeys.add(this.keys.actionCurrent(this.userId, purpose));
    }
  }

  @AfterEach
  protected void deleteOnlyKeysCreatedHere() {
    this.redis.delete(this.createdKeys);
  }

  /** 새 토큰 식별자. 정리 대상에 함께 등록해 둔다. */
  protected String newJti(AccountActionPurpose purpose) {
    String jti = UUID.randomUUID().toString();
    this.createdKeys.add(this.keys.actionToken(this.userId, purpose, jti));
    return jti;
  }

  /** 토큰 원문 대신 해시만 다룬다. 저장소에 원문이 들어갈 경로 자체를 만들지 않는다. */
  protected String someHash() {
    return CompactTokenHasher.hash("header.payload." + UUID.randomUUID());
  }

  protected ActionTokenStore.TokenRecord record(
      AccountActionPurpose purpose, String jti, String tokenHash) {
    return new ActionTokenStore.TokenRecord(this.userId, purpose, jti, tokenHash, this.expiresAt);
  }

  protected ActionTokenStore.TokenRecord recordAt(
      AccountActionPurpose purpose, String jti, String tokenHash, long expiresAt) {
    return new ActionTokenStore.TokenRecord(this.userId, purpose, jti, tokenHash, expiresAt);
  }

  protected Map<String, String> tokenState(AccountActionPurpose purpose, String jti) {
    Map<String, String> entries = new LinkedHashMap<>();
    this.redis
        .opsForHash()
        .entries(this.keys.actionToken(this.userId, purpose, jti))
        .forEach((field, value) -> entries.put(String.valueOf(field), String.valueOf(value)));
    return entries;
  }

  protected String currentJti(AccountActionPurpose purpose) {
    return this.redis.opsForValue().get(this.keys.actionCurrent(this.userId, purpose));
  }

  protected Long expireTimeOfToken(AccountActionPurpose purpose, String jti) {
    return this.redis.execute(
        EXPIRE_TIME_SCRIPT, List.of(this.keys.actionToken(this.userId, purpose, jti)));
  }

  protected Long expireTimeOfCurrent(AccountActionPurpose purpose) {
    return this.redis.execute(
        EXPIRE_TIME_SCRIPT, List.of(this.keys.actionCurrent(this.userId, purpose)));
  }
}
