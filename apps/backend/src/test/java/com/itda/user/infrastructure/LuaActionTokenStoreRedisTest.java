package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.support.redis.RequiresRealRedis;
import com.itda.user.application.port.out.ActionTokenConsumeResult;
import com.itda.user.application.port.out.ActionTokenIssueResult;
import com.itda.user.application.port.out.ActionTokenLookupResult;
import com.itda.user.application.port.out.ActionTokenRecord;
import com.itda.user.domain.AccountActionPurpose;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 계정 작업 토큰 상태 저장소를 실제 Redis에 대고 검증한다 (API 명세 3.6.1절, ERD 3.1.1절).
 *
 * <p>계약(3.6.1절 「동시성 검증」)이 "동일 action token 동시 소비에서 하나만 성공하는 것"을 실제 Redis로 확인하라고 요구한다. mock으로는 증명되지
 * 않는다.
 *
 * <p>토큰 원문을 만들지 않는다. 저장소가 다루는 값은 해시뿐이므로 테스트도 해시만 넘긴다.
 */
@SpringBootTest
@RequiresRealRedis
class LuaActionTokenStoreRedisTest extends ActionTokenRedisTestBase {

  private static final AccountActionPurpose EMAIL = AccountActionPurpose.EMAIL_VERIFICATION;
  private static final AccountActionPurpose RESET = AccountActionPurpose.PASSWORD_RESET;

  // ---------- 발급 ----------

  @Test
  void 발급하면_ACTIVE로_저장되고_action_current가_가리킨다() {
    String jti = newJti(EMAIL);
    String tokenHash = someHash();

    ActionTokenIssueResult result = this.store.issueActionToken(record(EMAIL, jti, tokenHash));

    assertThat(result).isEqualTo(ActionTokenIssueResult.ISSUED);
    assertThat(tokenState(EMAIL, jti))
        .containsEntry("userId", Long.toString(this.userId))
        .containsEntry("purpose", "EMAIL_VERIFICATION")
        .containsEntry("tokenHash", tokenHash)
        .containsEntry("expiresAt", Long.toString(this.expiresAt))
        .containsEntry("status", "ACTIVE");
    assertThat(currentJti(EMAIL)).isEqualTo(jti);
    // 계약: token 키와 action-current 키는 모두 그 action token의 exp에 만료한다.
    assertThat(expireTimeOfToken(EMAIL, jti)).isEqualTo(this.expiresAt);
    assertThat(expireTimeOfCurrent(EMAIL)).isEqualTo(this.expiresAt);
  }

  @Test
  void 같은_jti로_다시_발급하면_ALREADY_EXISTS이고_상태가_그대로다() {
    String jti = newJti(EMAIL);
    this.store.issueActionToken(record(EMAIL, jti, someHash()));
    Map<String, String> before = tokenState(EMAIL, jti);

    ActionTokenIssueResult result = this.store.issueActionToken(record(EMAIL, jti, someHash()));

    assertThat(result).isEqualTo(ActionTokenIssueResult.ALREADY_EXISTS);
    assertThat(tokenState(EMAIL, jti)).isEqualTo(before);
  }

  @Test
  void 새로_발급하면_같은_용도의_이전_활성_토큰이_INVALIDATED된다() {
    String firstJti = newJti(EMAIL);
    this.store.issueActionToken(record(EMAIL, firstJti, someHash()));
    String secondJti = newJti(EMAIL);

    this.store.issueActionToken(record(EMAIL, secondJti, someHash()));

    assertThat(tokenState(EMAIL, firstJti)).containsEntry("status", "INVALIDATED");
    assertThat(tokenState(EMAIL, secondJti)).containsEntry("status", "ACTIVE");
    assertThat(currentJti(EMAIL)).isEqualTo(secondJti);
    // 무효화해도 만료는 앞당기지 않는다. 재제출을 '없는 토큰'과 구분해야 한다.
    assertThat(expireTimeOfToken(EMAIL, firstJti)).isEqualTo(this.expiresAt);
  }

  @Test
  void 다른_용도의_활성_토큰은_건드리지_않는다() {
    String emailJti = newJti(EMAIL);
    this.store.issueActionToken(record(EMAIL, emailJti, someHash()));
    String resetJti = newJti(RESET);

    this.store.issueActionToken(record(RESET, resetJti, someHash()));

    assertThat(tokenState(EMAIL, emailJti)).containsEntry("status", "ACTIVE");
    assertThat(currentJti(EMAIL)).isEqualTo(emailJti);
    assertThat(currentJti(RESET)).isEqualTo(resetJti);
  }

  @Test
  void 이미_지난_만료로는_Redis를_부르지_않고_발급을_거부한다() {
    ActionTokenRecord expired = recordAt(EMAIL, newJti(EMAIL), someHash(), 1_000_000_000L);

    assertThatThrownBy(() -> this.store.issueActionToken(expired))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(currentJti(EMAIL)).isNull();
  }

  // ---------- 조회 ----------

  @Test
  void 방금_발급한_토큰은_ACTIVE로_조회된다() {
    String jti = newJti(EMAIL);
    String tokenHash = someHash();
    this.store.issueActionToken(record(EMAIL, jti, tokenHash));

    assertThat(this.store.lookup(record(EMAIL, jti, tokenHash)))
        .isEqualTo(ActionTokenLookupResult.ACTIVE);
  }

  @Test
  void 없는_토큰과_해시가_다른_토큰을_구분한다() {
    String jti = newJti(EMAIL);
    assertThat(this.store.lookup(record(EMAIL, jti, someHash())))
        .isEqualTo(ActionTokenLookupResult.NOT_FOUND);

    this.store.issueActionToken(record(EMAIL, jti, someHash()));
    assertThat(this.store.lookup(record(EMAIL, jti, someHash())))
        .isEqualTo(ActionTokenLookupResult.HASH_MISMATCH);
  }

  @Test
  void 재발급으로_밀려난_토큰은_NOT_CURRENT로_조회된다() {
    String firstJti = newJti(EMAIL);
    String firstHash = someHash();
    this.store.issueActionToken(record(EMAIL, firstJti, firstHash));
    // 무효화 전 상태를 만들기 위해 status만 ACTIVE로 되돌린다. current는 새 토큰을 가리킨다.
    this.store.issueActionToken(record(EMAIL, newJti(EMAIL), someHash()));
    this.redis
        .opsForHash()
        .put(this.keys.actionToken(this.userId, EMAIL, firstJti), "status", "ACTIVE");

    assertThat(this.store.lookup(record(EMAIL, firstJti, firstHash)))
        .isEqualTo(ActionTokenLookupResult.NOT_CURRENT);
  }

  @Test
  void 무효화된_토큰과_소비된_토큰을_구분해_조회한다() {
    String firstJti = newJti(EMAIL);
    String firstHash = someHash();
    this.store.issueActionToken(record(EMAIL, firstJti, firstHash));
    this.store.issueActionToken(record(EMAIL, newJti(EMAIL), someHash()));

    assertThat(this.store.lookup(record(EMAIL, firstJti, firstHash)))
        .isEqualTo(ActionTokenLookupResult.INVALIDATED);

    String consumedJti = newJti(RESET);
    String consumedHash = someHash();
    this.store.issueActionToken(record(RESET, consumedJti, consumedHash));
    this.store.consume(record(RESET, consumedJti, consumedHash));

    assertThat(this.store.lookup(record(RESET, consumedJti, consumedHash)))
        .isEqualTo(ActionTokenLookupResult.CONSUMED);
  }

  @Test
  void 저장된_값이_어긋나면_MISMATCHED로_조회된다() {
    String jti = newJti(EMAIL);
    String tokenHash = someHash();
    this.store.issueActionToken(record(EMAIL, jti, tokenHash));
    this.redis
        .opsForHash()
        .put(this.keys.actionToken(this.userId, EMAIL, jti), "purpose", "PASSWORD_RESET");

    assertThat(this.store.lookup(record(EMAIL, jti, tokenHash)))
        .isEqualTo(ActionTokenLookupResult.MISMATCHED);
  }

  // ---------- 소비 ----------

  @Test
  void 소비하면_CONSUMED가_되고_action_current가_정리된다() {
    String jti = newJti(EMAIL);
    String tokenHash = someHash();
    this.store.issueActionToken(record(EMAIL, jti, tokenHash));

    ActionTokenConsumeResult result = this.store.consume(record(EMAIL, jti, tokenHash));

    assertThat(result).isEqualTo(ActionTokenConsumeResult.CONSUMED);
    assertThat(tokenState(EMAIL, jti)).containsEntry("status", "CONSUMED");
    assertThat(currentJti(EMAIL)).isNull();
    // CONSUMED 표시는 원래 exp까지 남는다.
    assertThat(expireTimeOfToken(EMAIL, jti)).isEqualTo(this.expiresAt);
  }

  @Test
  void 소비한_토큰을_다시_제출하면_ALREADY_CONSUMED다() {
    String jti = newJti(EMAIL);
    String tokenHash = someHash();
    this.store.issueActionToken(record(EMAIL, jti, tokenHash));
    this.store.consume(record(EMAIL, jti, tokenHash));

    assertThat(this.store.consume(record(EMAIL, jti, tokenHash)))
        .isEqualTo(ActionTokenConsumeResult.ALREADY_CONSUMED);
  }

  @Test
  void 무효화된_토큰은_소비되지_않는다() {
    String firstJti = newJti(EMAIL);
    String firstHash = someHash();
    this.store.issueActionToken(record(EMAIL, firstJti, firstHash));
    this.store.issueActionToken(record(EMAIL, newJti(EMAIL), someHash()));

    assertThat(this.store.consume(record(EMAIL, firstJti, firstHash)))
        .isEqualTo(ActionTokenConsumeResult.INVALIDATED);
  }

  @Test
  void 현재_토큰이_아니면_소비되지_않는다() {
    String firstJti = newJti(EMAIL);
    String firstHash = someHash();
    this.store.issueActionToken(record(EMAIL, firstJti, firstHash));
    this.store.issueActionToken(record(EMAIL, newJti(EMAIL), someHash()));
    this.redis
        .opsForHash()
        .put(this.keys.actionToken(this.userId, EMAIL, firstJti), "status", "ACTIVE");

    assertThat(this.store.consume(record(EMAIL, firstJti, firstHash)))
        .isEqualTo(ActionTokenConsumeResult.NOT_CURRENT);
  }

  @Test
  void 해시가_다르면_상태를_바꾸지_않고_HASH_MISMATCH다() {
    String jti = newJti(EMAIL);
    this.store.issueActionToken(record(EMAIL, jti, someHash()));
    Map<String, String> before = tokenState(EMAIL, jti);

    assertThat(this.store.consume(record(EMAIL, jti, someHash())))
        .isEqualTo(ActionTokenConsumeResult.HASH_MISMATCH);
    assertThat(tokenState(EMAIL, jti)).isEqualTo(before);
    assertThat(currentJti(EMAIL)).isEqualTo(jti);
  }

  @Test
  void 저장된_만료와_제출한_만료가_다르면_소비하지_않는다() {
    String jti = newJti(EMAIL);
    String tokenHash = someHash();
    this.store.issueActionToken(record(EMAIL, jti, tokenHash));
    Map<String, String> before = tokenState(EMAIL, jti);

    assertThat(this.store.consume(recordAt(EMAIL, jti, tokenHash, this.expiresAt - 1)))
        .isEqualTo(ActionTokenConsumeResult.NOT_FOUND);
    assertThat(tokenState(EMAIL, jti)).isEqualTo(before);
  }

  @Test
  void 같은_토큰을_동시에_소비하면_정확히_하나만_성공한다() throws Exception {
    int attempts = 8;
    String jti = newJti(EMAIL);
    String tokenHash = someHash();
    this.store.issueActionToken(record(EMAIL, jti, tokenHash));
    ActionTokenRecord submitted = record(EMAIL, jti, tokenHash);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<ActionTokenConsumeResult>> consumes =
        IntStream.range(0, attempts)
            .<Callable<ActionTokenConsumeResult>>mapToObj(
                i ->
                    () -> {
                      start.await();
                      return this.store.consume(submitted);
                    })
            .toList();

    List<ActionTokenConsumeResult> results = runConcurrently(consumes, start);

    assertThat(results).filteredOn(ActionTokenConsumeResult.CONSUMED::equals).hasSize(1);
    assertThat(results).doesNotContain(ActionTokenConsumeResult.UNAVAILABLE);
    // 나머지는 이미 소비된 토큰의 재제출이다.
    assertThat(results)
        .filteredOn(result -> result != ActionTokenConsumeResult.CONSUMED)
        .containsOnly(ActionTokenConsumeResult.ALREADY_CONSUMED);
    assertThat(currentJti(EMAIL)).isNull();
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
  void 저장된_값에는_토큰_원문이_없다() {
    String sampleCompactValue = "header.payload." + java.util.UUID.randomUUID();
    String jti = newJti(EMAIL);
    this.store.issueActionToken(record(EMAIL, jti, CompactTokenHasher.hash(sampleCompactValue)));

    assertThat(tokenState(EMAIL, jti).values())
        .noneMatch(value -> value.contains(sampleCompactValue));
    assertThat(currentJti(EMAIL)).doesNotContain(sampleCompactValue);
  }

  // ---------- 동시 발급 ----------

  @Test
  void 연속으로_발급하면_이전_토큰들이_모두_INVALIDATED가_된다() {
    List<String> jtis = List.of(newJti(EMAIL), newJti(EMAIL), newJti(EMAIL));

    for (String jti : jtis) {
      assertThat(this.store.issueActionToken(record(EMAIL, jti, someHash())))
          .isEqualTo(ActionTokenIssueResult.ISSUED);
    }

    assertThat(tokenState(EMAIL, jtis.get(0))).containsEntry("status", "INVALIDATED");
    assertThat(tokenState(EMAIL, jtis.get(1))).containsEntry("status", "INVALIDATED");
    assertThat(tokenState(EMAIL, jtis.get(2))).containsEntry("status", "ACTIVE");
    assertThat(currentJti(EMAIL)).isEqualTo(jtis.get(2));
  }

  @Test
  void 같은_사용자_용도로_동시에_발급해도_ACTIVE_토큰은_하나만_남는다() throws Exception {
    int attempts = 8;
    // jti 등록은 정리 목록을 건드리므로 스레드를 띄우기 전에 끝낸다.
    List<String> jtis = IntStream.range(0, attempts).mapToObj(i -> newJti(EMAIL)).toList();
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<ActionTokenIssueResult>> issues =
        jtis.stream()
            .<Callable<ActionTokenIssueResult>>map(
                jti ->
                    () -> {
                      start.await();
                      return this.store.issueActionToken(record(EMAIL, jti, someHash()));
                    })
            .toList();

    List<ActionTokenIssueResult> results = runConcurrently(issues, start);

    // 같은 jti가 겹칠 일은 없으므로 ALREADY_EXISTS는 나오지 않는다.
    assertThat(results).doesNotContain(ActionTokenIssueResult.ALREADY_EXISTS);
    assertThat(results).contains(ActionTokenIssueResult.ISSUED);

    String current = currentJti(EMAIL);
    assertThat(current).isNotNull();
    List<String> active = new ArrayList<>();
    for (String jti : jtis) {
      Map<String, String> state = tokenState(EMAIL, jti);
      if (state.isEmpty()) {
        // 경쟁에서 밀려 아무것도 쓰지 않은 요청이다. 실패한 발급이 상태를 남기지 않았다는 뜻이다.
        continue;
      }
      if ("ACTIVE".equals(state.get("status"))) {
        active.add(jti);
      } else {
        assertThat(state).containsEntry("status", "INVALIDATED");
      }
      // 만료는 원래 exp 그대로다. 앞당기지도 연장하지도 않는다.
      assertThat(expireTimeOfToken(EMAIL, jti)).isEqualTo(this.expiresAt);
    }

    // 계약의 핵심: current가 아닌데 ACTIVE로 남은 토큰이 없다.
    assertThat(active).containsExactly(current);
    assertThat(expireTimeOfCurrent(EMAIL)).isEqualTo(this.expiresAt);
  }

  @Test
  void 동시_발급_중_소비가_끼어들어도_상태가_어긋나지_않는다() throws Exception {
    String consumedJti = newJti(EMAIL);
    String consumedHash = someHash();
    this.store.issueActionToken(record(EMAIL, consumedJti, consumedHash));
    List<String> jtis = IntStream.range(0, 4).mapToObj(i -> newJti(EMAIL)).toList();
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<String>> tasks = new ArrayList<>();
    tasks.add(
        () -> {
          start.await();
          return this.store.consume(record(EMAIL, consumedJti, consumedHash)).name();
        });
    jtis.forEach(
        jti ->
            tasks.add(
                () -> {
                  start.await();
                  return this.store.issueActionToken(record(EMAIL, jti, someHash())).name();
                }));

    List<String> results = runConcurrently(tasks, start);

    // 소비는 성공했거나(그 토큰이 아직 current였다) 밀렸다. 어느 쪽이든 성공을 가장하지 않는다.
    assertThat(results.get(0))
        .isIn(
            ActionTokenConsumeResult.CONSUMED.name(),
            ActionTokenConsumeResult.NOT_CURRENT.name(),
            ActionTokenConsumeResult.INVALIDATED.name());

    String current = currentJti(EMAIL);
    List<String> active = new ArrayList<>();
    List<String> all = new ArrayList<>(jtis);
    all.add(consumedJti);
    for (String jti : all) {
      Map<String, String> state = tokenState(EMAIL, jti);
      if (state.isEmpty()) {
        continue;
      }
      if ("ACTIVE".equals(state.get("status"))) {
        active.add(jti);
      } else {
        assertThat(state.get("status")).isIn("INVALIDATED", "CONSUMED");
      }
    }
    if (current == null) {
      // 소비가 마지막에 끝나 action-current를 지웠다. 그러면 활성 토큰도 없어야 한다.
      assertThat(active).isEmpty();
    } else {
      assertThat(active).containsExactly(current);
    }
    // 소비에 성공했다면 그 토큰은 CONSUMED로 남고 되살아나지 않는다.
    if (ActionTokenConsumeResult.CONSUMED.name().equals(results.get(0))) {
      assertThat(tokenState(EMAIL, consumedJti)).containsEntry("status", "CONSUMED");
    }
  }
}
