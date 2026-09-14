package com.itda.user.repository;

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
 * 실제 Redis를 쓰는 Refresh 상태 테스트의 공통 준비.
 *
 * <p><b>FLUSHDB·FLUSHALL을 쓰지 않는다.</b> 개발자의 로컬 Redis에는 다른 데이터가 있고, 테스트가 그것을 지우는 것은 테스트의 권한 밖이다. 대신
 * 실행마다 새 {@code userId}·{@code familyId}·{@code jti}를 만들어 서로 간섭하지 않게 하고, 정리 단계에서 이 테스트가 만든 키만 골라
 * 지운다.
 */
abstract class RefreshTokenRedisTestBase {

  /** 운영 데이터와 겹칠 수 없는 대역에서 시작한다. prefix도 test라 키 공간 자체가 분리돼 있다. */
  private static final AtomicLong USER_ID_SEQUENCE = new AtomicLong(9_000_000_000L);

  private static final long REFRESH_TTL_SECONDS = 14L * 24 * 60 * 60;

  /** key의 절대 만료 시각을 읽는다. -1은 TTL 없음, -2는 key 없음 (Redis 7.0+). */
  private static final RedisScript<Long> EXPIRE_TIME_SCRIPT =
      RedisScript.of("return redis.call('EXPIRETIME', KEYS[1])", Long.class);

  @Autowired protected RefreshTokenStore store;
  @Autowired protected StringRedisTemplate redis;
  @Autowired protected AuthRedisKeys keys;

  protected long userId;
  protected String familyId;

  /** 한 테스트 안에서는 같은 만료 시각을 쓴다. 등록과 조회가 서로 다른 초를 쓰면 검증된 metadata가 저장값과 어긋나 테스트가 시간에 따라 흔들린다. */
  protected long expiresAt;

  private final List<String> createdKeys = new ArrayList<>();

  @BeforeEach
  void prepareIdentifiers() {
    this.userId = USER_ID_SEQUENCE.incrementAndGet();
    this.familyId = UUID.randomUUID().toString();
    this.expiresAt = Instant.now().getEpochSecond() + REFRESH_TTL_SECONDS;
    this.createdKeys.add(this.keys.family(this.userId, this.familyId));
    this.createdKeys.add(this.keys.families(this.userId));
  }

  @AfterEach
  void deleteOnlyKeysCreatedHere() {
    this.redis.delete(this.createdKeys);
  }

  /** 새 토큰 식별자. 정리 대상에 함께 등록해 둔다. */
  protected String newJti() {
    String jti = UUID.randomUUID().toString();
    this.createdKeys.add(this.keys.refreshToken(this.userId, jti));
    return jti;
  }

  /** 다른 계열을 쓰는 시나리오에서도 정리 대상에 포함시킨다. */
  protected String newFamilyId() {
    String otherFamilyId = UUID.randomUUID().toString();
    this.createdKeys.add(this.keys.family(this.userId, otherFamilyId));
    return otherFamilyId;
  }

  /** 토큰 원문 대신 해시만 다룬다. 저장소에 원문이 들어갈 경로 자체를 만들지 않는다. */
  protected RefreshTokenStore.TokenRecord record(String familyId, String jti, String tokenHash) {
    return new RefreshTokenStore.TokenRecord(this.userId, familyId, jti, tokenHash, this.expiresAt);
  }

  protected RefreshTokenStore.TokenRecord record(String jti, String tokenHash) {
    return record(this.familyId, jti, tokenHash);
  }

  /** 만료 시각까지 어긋난 경우를 만들 때 쓴다. */
  protected RefreshTokenStore.TokenRecord recordAt(String jti, String tokenHash, long expiresAt) {
    return new RefreshTokenStore.TokenRecord(this.userId, this.familyId, jti, tokenHash, expiresAt);
  }

  protected Map<String, String> tokenState(String jti) {
    return hashAt(this.keys.refreshToken(this.userId, jti));
  }

  protected Map<String, String> familyState(String familyId) {
    return hashAt(this.keys.family(this.userId, familyId));
  }

  protected Map<String, String> familyState() {
    return familyState(this.familyId);
  }

  protected Long ttlOfToken(String jti) {
    return this.redis.getExpire(this.keys.refreshToken(this.userId, jti));
  }

  protected Long ttlOfFamilies() {
    return this.redis.getExpire(this.keys.families(this.userId));
  }

  protected Long ttlOfFamily() {
    return this.redis.getExpire(this.keys.family(this.userId, this.familyId));
  }

  /**
   * key의 절대 만료 시각(epoch seconds)을 읽는다. 남은 TTL은 초가 지나면 값이 달라져 "변하지 않았다"를 확인하기 어렵다. -1은 TTL 없음, -2는
   * key 없음이다 (Redis 7.0+).
   */
  protected Long expireTimeOf(String key) {
    // Lettuce의 임의 명령 실행 경로를 쓰지 않고 서버에서 직접 호출한다.
    return this.redis.execute(EXPIRE_TIME_SCRIPT, List.of(key));
  }

  protected Long expireTimeOfToken(String jti) {
    return expireTimeOf(this.keys.refreshToken(this.userId, jti));
  }

  protected Long expireTimeOfFamily() {
    return expireTimeOf(this.keys.family(this.userId, this.familyId));
  }

  protected Long expireTimeOfFamilies() {
    return expireTimeOf(this.keys.families(this.userId));
  }

  protected Double familiesScore(String familyId) {
    return this.redis.opsForZSet().score(this.keys.families(this.userId), familyId);
  }

  private Map<String, String> hashAt(String key) {
    Map<String, String> entries = new LinkedHashMap<>();
    this.redis
        .opsForHash()
        .entries(key)
        .forEach((field, value) -> entries.put(String.valueOf(field), String.valueOf(value)));
    return entries;
  }
}
