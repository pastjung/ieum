package com.itda.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.support.redis.RequiresRealRedis;
import com.itda.user.service.CompactTokenHasher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;

/**
 * Refresh 상태 저장소를 실제 Redis에 대고 검증한다 (API 명세 3.6.1절, ERD 3.1.1절).
 *
 * <p>가짜 구현으로는 이 이슈의 핵심이 증명되지 않는다. 동시 회전에서 하나만 성공하는 것, 재사용 탐지와 계열 폐기가 같은 실행 안에서 끝나는 것, {@code
 * EVALSHA} 캐시가 비워져도 복구되는 것은 모두 Redis 서버의 동작이다.
 *
 * <p>토큰 원문을 만들지 않는다. 저장소가 다루는 값은 해시뿐이므로 테스트도 해시만 넘긴다.
 */
@SpringBootTest
@RequiresRealRedis
class LuaRefreshTokenStoreRedisTest extends RefreshTokenRedisTestBase {

  /** docs/product/erd.md 3.1.1·7절이 고정한 버전. compose와 Dockerfile test 스테이지가 같은 값을 쓴다. */
  private static final String EXPECTED_REDIS_VERSION = "8.2.9";

  private static final long TTL_TOLERANCE_SECONDS = 120;
  private static final long REFRESH_TTL_SECONDS = 14L * 24 * 60 * 60;

  private static String someHash() {
    return CompactTokenHasher.hash("header.payload." + UUID.randomUUID());
  }

  @Test
  void Redis_서버_버전이_문서_기준과_같다() {
    Properties info =
        this.redis.execute(
            (RedisCallback<Properties>) connection -> connection.serverCommands().info("server"));

    assertThat(info).isNotNull();
    assertThat(info.getProperty("redis_version")).isEqualTo(EXPECTED_REDIS_VERSION);
  }

  @Test
  void 계열과_첫_토큰을_등록한다() {
    String jti = newJti();
    String tokenHash = someHash();

    RefreshTokenStore.FamilyIssueResult result = this.store.issueFamily(record(jti, tokenHash));

    assertThat(result).isEqualTo(RefreshTokenStore.FamilyIssueResult.ISSUED);
    assertThat(tokenState(jti))
        .containsEntry("userId", Long.toString(this.userId))
        .containsEntry("familyId", this.familyId)
        .containsEntry("tokenHash", tokenHash)
        .containsEntry("status", "ACTIVE");
    assertThat(familyState())
        .containsEntry("userId", Long.toString(this.userId))
        .containsEntry("currentJti", jti)
        .containsEntry("status", "ACTIVE");
    assertThat(familiesScore(this.familyId)).isNotNull();
    assertThat(ttlOfToken(jti))
        .isBetween(REFRESH_TTL_SECONDS - TTL_TOLERANCE_SECONDS, REFRESH_TTL_SECONDS);
    assertThat(ttlOfFamily())
        .isBetween(REFRESH_TTL_SECONDS - TTL_TOLERANCE_SECONDS, REFRESH_TTL_SECONDS);
  }

  @Test
  void 같은_식별자로_다시_등록하면_ALREADY_EXISTS이고_상태가_그대로다() {
    String jti = newJti();
    String tokenHash = someHash();
    this.store.issueFamily(record(jti, tokenHash));
    Map<String, String> beforeToken = tokenState(jti);
    Map<String, String> beforeFamily = familyState();

    RefreshTokenStore.FamilyIssueResult result = this.store.issueFamily(record(jti, someHash()));

    assertThat(result).isEqualTo(RefreshTokenStore.FamilyIssueResult.ALREADY_EXISTS);
    assertThat(tokenState(jti)).isEqualTo(beforeToken);
    assertThat(familyState()).isEqualTo(beforeFamily);
  }

  @Test
  void 회전하면_옛_토큰은_ROTATED_새_토큰은_ACTIVE가_된다() {
    String currentJti = newJti();
    String nextJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    RefreshTokenStore.TokenRecord next = record(nextJti, someHash());
    this.store.issueFamily(current);

    RefreshTokenStore.RotationResult result = this.store.rotate(current, next);

    assertThat(result).isEqualTo(RefreshTokenStore.RotationResult.ROTATED);
    assertThat(tokenState(currentJti)).containsEntry("status", "ROTATED");
    assertThat(tokenState(nextJti))
        .containsEntry("status", "ACTIVE")
        .containsEntry("tokenHash", next.tokenHash());
    assertThat(familyState())
        .containsEntry("currentJti", nextJti)
        .containsEntry("status", "ACTIVE");
  }

  @Test
  void 회전해도_옛_토큰의_만료는_앞당겨지지_않는다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    Long beforeTtl = ttlOfToken(currentJti);

    this.store.rotate(current, record(newJti(), someHash()));

    // 아직 수명이 남은 옛 토큰의 재사용을 계속 판별할 수 있어야 한다.
    assertThat(ttlOfToken(currentJti)).isGreaterThanOrEqualTo(beforeTtl - TTL_TOLERANCE_SECONDS);
    assertThat(ttlOfToken(currentJti)).isLessThanOrEqualTo(REFRESH_TTL_SECONDS);
  }

  @Test
  void 회전하면_새_토큰의_만료와_계열_index_score가_갱신된다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    Double beforeScore = familiesScore(this.familyId);
    String nextJti = newJti();
    RefreshTokenStore.TokenRecord next = record(nextJti, someHash());

    this.store.rotate(current, next);

    assertThat(ttlOfToken(nextJti))
        .isBetween(REFRESH_TTL_SECONDS - TTL_TOLERANCE_SECONDS, REFRESH_TTL_SECONDS);
    assertThat(ttlOfFamily())
        .isBetween(REFRESH_TTL_SECONDS - TTL_TOLERANCE_SECONDS, REFRESH_TTL_SECONDS);
    assertThat(familiesScore(this.familyId))
        .isNotNull()
        .isEqualTo((double) next.expiresAtEpochSecond())
        .isGreaterThanOrEqualTo(beforeScore);
  }

  @Test
  void 같은_토큰으로_동시에_회전하면_정확히_하나만_성공한다() throws Exception {
    int attempts = 8;
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<RefreshTokenStore.RotationResult>> rotations =
        IntStream.range(0, attempts)
            .<Callable<RefreshTokenStore.RotationResult>>mapToObj(
                i -> {
                  RefreshTokenStore.TokenRecord next = record(newJti(), someHash());
                  return () -> {
                    start.await();
                    return this.store.rotate(current, next);
                  };
                })
            .toList();

    List<RefreshTokenStore.RotationResult> results = runConcurrently(rotations, start);

    assertThat(results).filteredOn(RefreshTokenStore.RotationResult.ROTATED::equals).hasSize(1);
    assertThat(results).doesNotContain(RefreshTokenStore.RotationResult.UNAVAILABLE);
    // 나머지는 모두 거부된다. 첫 재제출이 이미 회전된 토큰이라 재사용으로 계열을 폐기하고,
    // 그 뒤의 요청은 폐기된 계열에 막힌다.
    assertThat(results).contains(RefreshTokenStore.RotationResult.REUSED);
    assertThat(results)
        .filteredOn(result -> result != RefreshTokenStore.RotationResult.ROTATED)
        .containsOnly(
            RefreshTokenStore.RotationResult.REUSED,
            RefreshTokenStore.RotationResult.FAMILY_REVOKED);
    assertThat(familyState()).containsEntry("status", "REVOKED");
  }

  private static <T> List<T> runConcurrently(List<Callable<T>> tasks, CountDownLatch start)
      throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
    try {
      List<Future<T>> futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(10, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void 이미_회전된_토큰을_다시_제출하면_재사용으로_보고_계열을_폐기한다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    this.store.rotate(current, record(newJti(), someHash()));

    RefreshTokenStore.RotationResult result =
        this.store.rotate(current, record(newJti(), someHash()));

    assertThat(result).isEqualTo(RefreshTokenStore.RotationResult.REUSED);
    assertThat(familyState()).containsEntry("status", "REVOKED");
    // 계열만 폐기한다. 토큰 상태는 재사용 판별 근거라 그대로 둔다.
    assertThat(tokenState(currentJti)).containsEntry("status", "ROTATED");
  }

  @Test
  void 계열이_폐기되면_그_뒤의_회전은_FAMILY_REVOKED다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    this.store.revokeFamily(this.userId, this.familyId);

    RefreshTokenStore.RotationResult result =
        this.store.rotate(current, record(newJti(), someHash()));

    assertThat(result).isEqualTo(RefreshTokenStore.RotationResult.FAMILY_REVOKED);
  }

  @Test
  void 계열의_현재_토큰이_아닌_ACTIVE_토큰은_재사용으로_본다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    this.store.rotate(current, record(newJti(), someHash()));
    // 회전 이력이 어긋난 상태를 만든다: 옛 토큰이 ACTIVE인데 계열의 현재 토큰은 다른 jti다.
    this.redis
        .opsForHash()
        .put(this.keys.refreshToken(this.userId, currentJti), "status", "ACTIVE");

    RefreshTokenStore.RotationResult result =
        this.store.rotate(current, record(newJti(), someHash()));

    assertThat(result).isEqualTo(RefreshTokenStore.RotationResult.REUSED);
    assertThat(familyState()).containsEntry("status", "REVOKED");
  }

  @Test
  void tokenHash가_다르면_계열을_폐기하지_않고_HASH_MISMATCH를_돌려준다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    RefreshTokenStore.TokenRecord forged = record(currentJti, someHash());

    RefreshTokenStore.RotationResult result =
        this.store.rotate(forged, record(newJti(), someHash()));

    assertThat(result).isEqualTo(RefreshTokenStore.RotationResult.HASH_MISMATCH);
    // 토큰을 갖고 있지 않은 요청이 계열을 끊을 수 있으면 그것이 곧 서비스 거부 수단이 된다.
    assertThat(familyState()).containsEntry("status", "ACTIVE");
    assertThat(tokenState(currentJti)).containsEntry("status", "ACTIVE");
  }

  @Test
  void 신규_jti가_이미_있으면_아무_상태도_바꾸지_않고_ALREADY_EXISTS다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    String takenJti = newJti();
    this.store.issueFamily(record(newFamilyId(), takenJti, someHash()));
    Map<String, String> beforeCurrent = tokenState(currentJti);
    Map<String, String> beforeTaken = tokenState(takenJti);
    Map<String, String> beforeFamily = familyState();

    RefreshTokenStore.RotationResult result =
        this.store.rotate(current, record(takenJti, someHash()));

    assertThat(result).isEqualTo(RefreshTokenStore.RotationResult.ALREADY_EXISTS);
    assertThat(tokenState(currentJti)).isEqualTo(beforeCurrent);
    assertThat(tokenState(takenJti)).isEqualTo(beforeTaken);
    assertThat(familyState()).isEqualTo(beforeFamily);
  }

  @Test
  void revokeFamily는_계열_상태만_바꾸고_토큰은_건드리지_않는다() {
    String jti = newJti();
    this.store.issueFamily(record(jti, someHash()));
    Map<String, String> beforeToken = tokenState(jti);
    Long beforeTokenTtl = ttlOfToken(jti);

    RefreshTokenStore.FamilyRevocationResult result =
        this.store.revokeFamily(this.userId, this.familyId);

    assertThat(result).isEqualTo(RefreshTokenStore.FamilyRevocationResult.REVOKED);
    assertThat(familyState()).containsEntry("status", "REVOKED").containsEntry("currentJti", jti);
    assertThat(tokenState(jti)).isEqualTo(beforeToken);
    assertThat(ttlOfToken(jti)).isGreaterThanOrEqualTo(beforeTokenTtl - TTL_TOLERANCE_SECONDS);
  }

  @Test
  void 없는_계열을_폐기하면_NOT_FOUND다() {
    assertThat(this.store.revokeFamily(this.userId, this.familyId))
        .isEqualTo(RefreshTokenStore.FamilyRevocationResult.NOT_FOUND);
  }

  @Test
  void SCRIPT_FLUSH로_캐시가_비워져도_회전이_복구된다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    // EVALSHA가 NOSCRIPT로 실패하는 상황을 만든다. 복구는 Spring Data Redis의 기본 실행 경로가 담당한다.
    this.redis.execute(
        (RedisCallback<Object>)
            connection -> {
              connection.scriptingCommands().scriptFlush();
              return null;
            });

    RefreshTokenStore.RotationResult result =
        this.store.rotate(current, record(newJti(), someHash()));

    assertThat(result).isEqualTo(RefreshTokenStore.RotationResult.ROTATED);
  }

  // ---------- 저장된 만료 시각과 key TTL (F-2) ----------

  /** 상태 전이가 하나도 일어나지 않았음을 확인할 수 있게 관련 값을 한 번에 모은다. */
  private record RedisState(
      Map<String, String> token,
      Long tokenExpireTime,
      Map<String, String> family,
      Long familyExpireTime,
      Double score,
      Long familiesExpireTime) {}

  private RedisState stateOf(String jti) {
    return new RedisState(
        tokenState(jti),
        expireTimeOfToken(jti),
        familyState(),
        expireTimeOfFamily(),
        familiesScore(this.familyId),
        expireTimeOfFamilies());
  }

  /** 거부된 회전은 기존 토큰·신규 토큰·계열·계열 index 어느 것도 바꾸지 않아야 한다. */
  private void assertRotationRejectedWithoutChange(
      RefreshTokenStore.TokenRecord current,
      String currentJti,
      RefreshTokenStore.RotationResult expected) {
    RedisState before = stateOf(currentJti);
    String nextJti = newJti();

    RefreshTokenStore.RotationResult result =
        this.store.rotate(current, record(nextJti, someHash()));

    assertThat(result).isEqualTo(expected);
    assertThat(stateOf(currentJti)).isEqualTo(before);
    assertThat(tokenState(nextJti)).isEmpty();
  }

  @Test
  void 저장된_만료_시각이_바뀌면_회전하지_않는다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    this.redis
        .opsForHash()
        .put(
            this.keys.refreshToken(this.userId, currentJti),
            "expiresAt",
            Long.toString(this.expiresAt - 1));

    assertRotationRejectedWithoutChange(
        current, currentJti, RefreshTokenStore.RotationResult.NOT_FOUND);
  }

  @Test
  void 만료_시각_field가_없으면_회전하지_않는다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    this.redis.opsForHash().delete(this.keys.refreshToken(this.userId, currentJti), "expiresAt");

    assertRotationRejectedWithoutChange(
        current, currentJti, RefreshTokenStore.RotationResult.NOT_FOUND);
  }

  @Test
  void 만료_시각이_정수_문자열이_아니면_회전하지_않는다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    this.redis
        .opsForHash()
        .put(this.keys.refreshToken(this.userId, currentJti), "expiresAt", this.expiresAt + ".0");

    assertRotationRejectedWithoutChange(
        current, currentJti, RefreshTokenStore.RotationResult.NOT_FOUND);
  }

  @Test
  void 제출한_만료가_저장된_값과_다르면_회전하지_않는다() {
    String currentJti = newJti();
    this.store.issueFamily(record(currentJti, someHash()));
    RefreshTokenStore.TokenRecord current =
        recordAt(currentJti, tokenState(currentJti).get("tokenHash"), this.expiresAt - 1);

    assertRotationRejectedWithoutChange(
        current, currentJti, RefreshTokenStore.RotationResult.NOT_FOUND);
  }

  @Test
  void key에_TTL이_없으면_회전하지_않는다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    this.redis.persist(this.keys.refreshToken(this.userId, currentJti));

    assertRotationRejectedWithoutChange(
        current, currentJti, RefreshTokenStore.RotationResult.NOT_FOUND);
  }

  @Test
  void key의_만료가_저장된_값과_다르면_회전하지_않는다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    this.redis.expireAt(
        this.keys.refreshToken(this.userId, currentJti),
        Instant.ofEpochSecond(this.expiresAt + 60));

    assertRotationRejectedWithoutChange(
        current, currentJti, RefreshTokenStore.RotationResult.NOT_FOUND);
  }

  @Test
  void 새_토큰의_만료가_기존보다_이르면_Redis를_부르기_전에_거부한다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    RefreshTokenStore.TokenRecord earlier = recordAt(newJti(), someHash(), this.expiresAt - 1);
    RedisState before = stateOf(currentJti);

    assertThatThrownBy(() -> this.store.rotate(current, earlier))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(stateOf(currentJti)).isEqualTo(before);
  }

  @Test
  void 회전이_계열과_계열_index의_만료를_앞당기지_않는다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    // 같은 계열의 다른 토큰이 더 늦게 만료되는 상황을 만든다.
    Instant later = Instant.ofEpochSecond(this.expiresAt + 600);
    this.redis.expireAt(this.keys.family(this.userId, this.familyId), later);
    this.redis.expireAt(this.keys.families(this.userId), later);

    assertThat(this.store.rotate(current, record(newJti(), someHash())))
        .isEqualTo(RefreshTokenStore.RotationResult.ROTATED);

    assertThat(expireTimeOfFamily()).isEqualTo(later.getEpochSecond());
    assertThat(expireTimeOfFamilies()).isEqualTo(later.getEpochSecond());
  }

  // ---------- 계열 index에만 남은 familyId (F-3) ----------

  @Test
  void 계열_index에만_member가_남아_있어도_ALREADY_EXISTS다() {
    // family hash가 TTL로 먼저 사라져도 ZSET member는 그 계열이 쓰였다는 기록으로 남는다.
    double existingScore = this.expiresAt - 100;
    this.redis.opsForZSet().add(this.keys.families(this.userId), this.familyId, existingScore);
    Long beforeFamiliesExpireTime = expireTimeOfFamilies();
    String jti = newJti();

    RefreshTokenStore.FamilyIssueResult result = this.store.issueFamily(record(jti, someHash()));

    assertThat(result).isEqualTo(RefreshTokenStore.FamilyIssueResult.ALREADY_EXISTS);
    assertThat(tokenState(jti)).isEmpty();
    assertThat(familyState()).isEmpty();
    assertThat(familiesScore(this.familyId)).isEqualTo(existingScore);
    assertThat(expireTimeOfFamilies()).isEqualTo(beforeFamiliesExpireTime);
  }

  // ---------- 재사용 판정은 저장값 손상에 가리지 않는다 (L-1) ----------

  /** 회전이 끝난 뒤 옛 토큰을 ROTATED 상태로 남겨 둔다. 반환값은 그 옛 토큰의 제출용 기록이다. */
  private RefreshTokenStore.TokenRecord rotatedToken() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    this.store.rotate(current, record(newJti(), someHash()));
    return current;
  }

  @Test
  void ROTATED_토큰의_만료_field가_손상돼도_재사용으로_계열을_폐기한다() {
    RefreshTokenStore.TokenRecord rotated = rotatedToken();
    this.redis
        .opsForHash()
        .put(this.keys.refreshToken(this.userId, rotated.jti()), "expiresAt", "corrupted");

    RefreshTokenStore.RotationResult result =
        this.store.rotate(rotated, record(newJti(), someHash()));

    // 저장값을 망가뜨려 폐기를 피하는 길이 있으면 재사용 탐지 자체가 무력해진다.
    assertThat(result).isEqualTo(RefreshTokenStore.RotationResult.REUSED);
    assertThat(familyState()).containsEntry("status", "REVOKED");
  }

  @Test
  void ROTATED_토큰의_TTL이_사라져도_재사용으로_계열을_폐기한다() {
    RefreshTokenStore.TokenRecord rotated = rotatedToken();
    this.redis.persist(this.keys.refreshToken(this.userId, rotated.jti()));

    RefreshTokenStore.RotationResult result =
        this.store.rotate(rotated, record(newJti(), someHash()));

    assertThat(result).isEqualTo(RefreshTokenStore.RotationResult.REUSED);
    assertThat(familyState()).containsEntry("status", "REVOKED");
  }

  @Test
  void 만료가_손상된_ROTATED_토큰이라도_해시를_모르면_계열을_폐기하지_못한다() {
    RefreshTokenStore.TokenRecord rotated = rotatedToken();
    this.redis
        .opsForHash()
        .put(this.keys.refreshToken(this.userId, rotated.jti()), "expiresAt", "corrupted");
    RefreshTokenStore.TokenRecord forged = record(rotated.jti(), someHash());

    assertThat(this.store.rotate(forged, record(newJti(), someHash())))
        .isEqualTo(RefreshTokenStore.RotationResult.HASH_MISMATCH);
    assertThat(familyState()).containsEntry("status", "ACTIVE");
  }

  @Test
  void TTL이_사라진_ROTATED_토큰이라도_해시를_모르면_계열을_폐기하지_못한다() {
    RefreshTokenStore.TokenRecord rotated = rotatedToken();
    this.redis.persist(this.keys.refreshToken(this.userId, rotated.jti()));
    RefreshTokenStore.TokenRecord forged = record(rotated.jti(), someHash());

    assertThat(this.store.rotate(forged, record(newJti(), someHash())))
        .isEqualTo(RefreshTokenStore.RotationResult.HASH_MISMATCH);
    assertThat(familyState()).containsEntry("status", "ACTIVE");
  }

  // ---------- 계열 index score는 앞당기지 않는다 (L-2) ----------

  @Test
  void 기존_score가_더_늦으면_회전해도_낮추지_않는다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    // 같은 계열에 더 늦게 만료되는 Refresh가 남아 있는 상태를 만든다.
    double laterScore = this.expiresAt + 600;
    this.redis.opsForZSet().add(this.keys.families(this.userId), this.familyId, laterScore);
    this.redis.expireAt(this.keys.families(this.userId), Instant.ofEpochSecond((long) laterScore));

    assertThat(this.store.rotate(current, record(newJti(), someHash())))
        .isEqualTo(RefreshTokenStore.RotationResult.ROTATED);

    assertThat(familiesScore(this.familyId)).isEqualTo(laterScore);
    assertThat(expireTimeOfFamilies()).isEqualTo((long) laterScore);
  }

  @Test
  void 계열_index에_member가_없으면_회전이_새로_추가한다() {
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    this.redis.opsForZSet().remove(this.keys.families(this.userId), this.familyId);
    String nextJti = newJti();
    RefreshTokenStore.TokenRecord next = record(nextJti, someHash());

    assertThat(this.store.rotate(current, next))
        .isEqualTo(RefreshTokenStore.RotationResult.ROTATED);

    assertThat(familiesScore(this.familyId)).isEqualTo((double) next.expiresAtEpochSecond());
  }

  @Test
  void 신규_토큰의_만료가_더_늦으면_계열과_index의_만료가_그만큼_연장된다() {
    // 운영 회전은 항상 next.exp > current.exp다. 이 경로에서만 ZADD GT의 상향 갱신과 만료 연장이 실행된다.
    long laterExpiresAt = this.expiresAt + 3600;
    String currentJti = newJti();
    RefreshTokenStore.TokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    String nextJti = newJti();
    RefreshTokenStore.TokenRecord next = recordAt(nextJti, someHash(), laterExpiresAt);

    assertThat(this.store.rotate(current, next))
        .isEqualTo(RefreshTokenStore.RotationResult.ROTATED);

    // 남은 TTL이 아니라 절대 만료 시각으로 본다. 초가 지나도 값이 흔들리지 않는다.
    assertThat(expireTimeOfToken(currentJti)).isEqualTo(this.expiresAt);
    assertThat(expireTimeOfToken(nextJti)).isEqualTo(laterExpiresAt);
    assertThat(expireTimeOfFamily()).isEqualTo(laterExpiresAt);
    assertThat(familiesScore(this.familyId)).isEqualTo((double) laterExpiresAt);
    assertThat(expireTimeOfFamilies()).isEqualTo(laterExpiresAt);
  }

  @Test
  void 저장된_값에는_토큰_원문이_없다() {
    String sampleCompactValue = "header.payload." + UUID.randomUUID();
    String jti = newJti();
    this.store.issueFamily(record(jti, CompactTokenHasher.hash(sampleCompactValue)));

    assertThat(tokenState(jti).values()).noneMatch(value -> value.contains(sampleCompactValue));
    assertThat(familyState().values()).noneMatch(value -> value.contains(sampleCompactValue));
  }
}
