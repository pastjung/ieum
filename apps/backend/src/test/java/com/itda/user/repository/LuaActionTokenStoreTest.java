package com.itda.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.itda.support.log.CapturingAppender;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.service.CompactTokenHasher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 계정 작업 토큰 저장소의 <b>장애·경쟁 실패 경로</b>를 실제 Redis 없이 고정한다.
 *
 * <p>정상 경로와 원자성은 {@link LuaActionTokenStoreRedisTest}가 실제 Redis 8.2.9에 대고 증명한다. 여기서 다루는 것은 실제
 * Redis로는 결정적으로 만들기 어려운 쪽이다 — 연결이 끊겼을 때, 스크립트가 약속하지 않은 값을 줬을 때, {@code action-current} 경쟁에서 계속 밀렸을
 * 때 어떤 결과를 내는가. 이 테스트로 원자성을 주장하지 않는다.
 *
 * <p>연결 실패·타임아웃을 재현하려고 진짜 네트워크 대기를 걸지 않는다. Spring Data Redis가 그 상황에서 던지는 예외를 그대로 던지게 해 변환 규칙만 검증한다.
 */
class LuaActionTokenStoreTest {

  private static final long USER_ID = 7L;
  private static final AccountActionPurpose PURPOSE = AccountActionPurpose.EMAIL_VERIFICATION;

  private static final AuthRedisKeys KEYS = new AuthRedisKeys(new AuthRedisProperties("test"));

  /** 2100-01-01. 만료 경계 검증이 시계에 따라 흔들리지 않게 충분히 먼 미래를 쓴다. */
  private static final long FUTURE_EPOCH_SECOND = 4_102_444_800L;

  /** 2001-09-09. 현재보다 충분히 과거라 초 경계에서 판정이 바뀌지 않는다. */
  private static final long PAST_EPOCH_SECOND = 1_000_000_000L;

  private static String someHash() {
    return CompactTokenHasher.hash("header.payload." + UUID.randomUUID());
  }

  private static ActionTokenStore.TokenRecord record() {
    return recordExpiringAt(FUTURE_EPOCH_SECOND);
  }

  private static ActionTokenStore.TokenRecord recordExpiringAt(long expiresAtEpochSecond) {
    return new ActionTokenStore.TokenRecord(
        USER_ID, PURPOSE, UUID.randomUUID().toString(), someHash(), expiresAtEpochSecond);
  }

  /** 만료 경계를 결정적으로 보기 위해 고정 시계를 쓴다. 실제 시스템 시간에 기대면 초 경계에서 흔들린다. */
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);

  private static ActionTokenStore storeOf(StringRedisTemplate template) {
    return storeOf(template, FIXED_CLOCK);
  }

  private static ActionTokenStore storeOf(StringRedisTemplate template, Clock clock) {
    return new LuaActionTokenStore(
        template,
        KEYS,
        script("issue-action-token.lua"),
        script("consume-action-token.lua"),
        clock);
  }

  private static RedisScript<String> script(String fileName) {
    return RedisScript.of(new ClassPathResource("redis/" + fileName), String.class);
  }

  private static ActionTokenStore failingStore(RuntimeException failure) {
    StringRedisTemplate template = new StringRedisTemplate(new FailingConnectionFactory(failure));
    template.afterPropertiesSet();
    return storeOf(template);
  }

  // ---------- 저장소 장애 ----------

  @Test
  void 연결_실패는_모든_연산에서_UNAVAILABLE이다() {
    ActionTokenStore store = failingStore(new RedisConnectionFailureException("연결 실패"));
    ActionTokenStore.TokenRecord token = record();

    assertThat(store.issueActionToken(token)).isEqualTo(ActionTokenStore.IssueResult.UNAVAILABLE);
    assertThat(store.lookup(token)).isEqualTo(ActionTokenStore.LookupResult.UNAVAILABLE);
    assertThat(store.consume(token)).isEqualTo(ActionTokenStore.ConsumeResult.UNAVAILABLE);
  }

  @Test
  void 명령_타임아웃도_UNAVAILABLE이다() {
    ActionTokenStore store = failingStore(new QueryTimeoutException("응답 없음"));

    assertThat(store.issueActionToken(record()))
        .isEqualTo(ActionTokenStore.IssueResult.UNAVAILABLE);
    assertThat(store.consume(record())).isEqualTo(ActionTokenStore.ConsumeResult.UNAVAILABLE);
  }

  @Test
  void 서버가_명령을_거부해도_UNAVAILABLE이다() {
    ActionTokenStore store =
        failingStore(new RedisSystemException("실행 실패", new IllegalStateException()));

    assertThat(store.consume(record())).isEqualTo(ActionTokenStore.ConsumeResult.UNAVAILABLE);
  }

  @Test
  void 스크립트_실행만_실패해도_발급은_UNAVAILABLE이다() {
    // action-current 읽기는 성공하고 스크립트 실행에서 끊긴 경우다.
    ScriptedRedisTemplate template = new ScriptedRedisTemplate(null);
    template.failScriptWith(new RedisConnectionFailureException("연결 실패"));

    assertThat(storeOf(template).issueActionToken(record()))
        .isEqualTo(ActionTokenStore.IssueResult.UNAVAILABLE);
  }

  @Test
  void 스크립트가_약속하지_않은_값을_주면_성공으로_단정하지_않는다() {
    assertThat(storeOf(new ScriptedRedisTemplate(null, "WAT")).issueActionToken(record()))
        .isEqualTo(ActionTokenStore.IssueResult.UNAVAILABLE);
    assertThat(storeOf(new ScriptedRedisTemplate(null, (String) null)).issueActionToken(record()))
        .isEqualTo(ActionTokenStore.IssueResult.UNAVAILABLE);
    assertThat(storeOf(new ScriptedRedisTemplate(null, "")).consume(record()))
        .isEqualTo(ActionTokenStore.ConsumeResult.UNAVAILABLE);
  }

  // ---------- action-current 경쟁 (adapter 내부 신호) ----------

  @Test
  void 경쟁에_밀리면_최신_action_current를_다시_읽어_재시도한다() {
    // 첫 시도는 밀리고 두 번째에 성공한다. 그 사이 action-current를 다시 읽어야 한다.
    ScriptedRedisTemplate template = new ScriptedRedisTemplate(null, "CONTENTION", "OK");

    assertThat(storeOf(template).issueActionToken(record()))
        .isEqualTo(ActionTokenStore.IssueResult.ISSUED);
    assertThat(template.currentReads()).isEqualTo(2);
    assertThat(template.scriptExecutions()).isEqualTo(2);
  }

  @Test
  void 경쟁_재시도_상한에_닿으면_성공으로_단정하지_않는다() {
    // 계속 밀리는 상황. 성공도 ALREADY_EXISTS도 아니므로 fail-closed여야 한다.
    ScriptedRedisTemplate template =
        new ScriptedRedisTemplate(null, "CONTENTION", "CONTENTION", "CONTENTION", "CONTENTION");

    assertThat(storeOf(template).issueActionToken(record()))
        .isEqualTo(ActionTokenStore.IssueResult.UNAVAILABLE);
    // 무한 재시도로 호출 스레드를 묶지 않는다.
    assertThat(template.scriptExecutions()).isEqualTo(3);
  }

  @Test
  void 경쟁_신호는_공개_결과에_존재하지_않는다() {
    // CONTENTION은 adapter 내부 전용이다. 공개 enum에 값이 생기면 HTTP 응답까지 새어 나갈 수 있다.
    assertThat(ActionTokenStore.IssueResult.values())
        .containsExactly(
            ActionTokenStore.IssueResult.ISSUED,
            ActionTokenStore.IssueResult.ALREADY_EXISTS,
            ActionTokenStore.IssueResult.UNAVAILABLE);
    assertThat(ActionTokenStore.IssueResult.values())
        .noneMatch(result -> result.name().contains("CONTENTION"));
  }

  @Test
  void 이전_토큰이_없으면_빈_기대값으로_넘긴다() {
    RecordingScriptedRedisTemplate template = new RecordingScriptedRedisTemplate(null, "OK");

    assertThat(storeOf(template).issueActionToken(record()))
        .isEqualTo(ActionTokenStore.IssueResult.ISSUED);

    // ARGV[6] = 기대하는 action-current. 없으면 빈 문자열이고, KEYS[3]은 신규 키와 같다.
    assertThat(template.lastArgs()[5]).isEqualTo("");
    assertThat(template.lastKeys().get(2)).isEqualTo(template.lastKeys().get(0));
  }

  @Test
  void 이전_토큰이_있으면_읽은_jti와_그_키를_함께_넘긴다() {
    String previousJti = UUID.randomUUID().toString();
    RecordingScriptedRedisTemplate template = new RecordingScriptedRedisTemplate(previousJti, "OK");

    assertThat(storeOf(template).issueActionToken(record()))
        .isEqualTo(ActionTokenStore.IssueResult.ISSUED);

    assertThat(template.lastArgs()[5]).isEqualTo(previousJti);
    assertThat(template.lastKeys().get(2))
        .isEqualTo(KEYS.actionToken(USER_ID, PURPOSE, previousJti));
  }

  // ---------- 입력 경계 ----------

  @Test
  void 만료가_현재_시각과_같으면_거부한다() {
    // 경계. EXPIREAT는 현재 시각 이하를 받으면 키를 지우므로 'OK'를 돌려주고도 저장된 것이 없는 상태가 된다.
    RecordingScriptedRedisTemplate template = new RecordingScriptedRedisTemplate(null, "OK");
    long now = FIXED_CLOCK.instant().getEpochSecond();

    assertThatThrownBy(() -> storeOf(template).issueActionToken(recordExpiringAt(now)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(template.scriptExecutions()).isZero();
  }

  @Test
  void 만료가_현재보다_1초_뒤면_허용한다() {
    RecordingScriptedRedisTemplate template = new RecordingScriptedRedisTemplate(null, "OK");
    long oneSecondLater = FIXED_CLOCK.instant().getEpochSecond() + 1;

    assertThat(storeOf(template).issueActionToken(recordExpiringAt(oneSecondLater)))
        .isEqualTo(ActionTokenStore.IssueResult.ISSUED);
    assertThat(template.scriptExecutions()).isEqualTo(1);
  }

  @Test
  void 시계를_주입하지_않으면_만들_수_없다() {
    assertThatThrownBy(() -> storeOf(new ScriptedRedisTemplate(null, "OK"), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 이미_지난_만료는_Redis를_부르기_전에_거부한다() {
    RecordingScriptedRedisTemplate template = new RecordingScriptedRedisTemplate(null, "OK");
    ActionTokenStore.TokenRecord expired =
        new ActionTokenStore.TokenRecord(
            USER_ID, PURPOSE, UUID.randomUUID().toString(), someHash(), PAST_EPOCH_SECOND);

    assertThatThrownBy(() -> storeOf(template).issueActionToken(expired))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(expired.jti())
        .hasMessageNotContaining(expired.tokenHash());
    assertThat(template.scriptExecutions()).isZero();
  }

  // ---------- 로그 ----------

  @Test
  void 장애_로그에_해시도_키도_예외도_남기지_않는다() {
    ActionTokenStore.TokenRecord token = record();
    ActionTokenStore store = failingStore(new RedisConnectionFailureException("연결 실패"));

    CapturingAppender appender = CapturingAppender.attachTo(LuaActionTokenStore.class);
    try {
      assertThat(store.issueActionToken(token)).isEqualTo(ActionTokenStore.IssueResult.UNAVAILABLE);

      List<ILoggingEvent> events = appender.events();
      assertThat(events).isNotEmpty();
      for (ILoggingEvent event : events) {
        assertThat(event.getFormattedMessage())
            .doesNotContain(token.tokenHash())
            .doesNotContain(token.jti())
            .doesNotContain(KEYS.actionToken(USER_ID, PURPOSE, token.jti()))
            .doesNotContain(KEYS.actionCurrent(USER_ID, PURPOSE))
            .doesNotContain("연결 실패");
        assertThat(event.getThrowableProxy()).isNull();
      }
    } finally {
      appender.detach();
    }
  }

  @Test
  void 경쟁_소진_로그에도_식별자를_남기지_않는다() {
    ActionTokenStore.TokenRecord token = record();
    ScriptedRedisTemplate template =
        new ScriptedRedisTemplate(null, "CONTENTION", "CONTENTION", "CONTENTION");

    CapturingAppender appender = CapturingAppender.attachTo(LuaActionTokenStore.class);
    try {
      assertThat(storeOf(template).issueActionToken(token))
          .isEqualTo(ActionTokenStore.IssueResult.UNAVAILABLE);

      assertThat(appender.events()).isNotEmpty();
      for (ILoggingEvent event : appender.events()) {
        assertThat(event.getFormattedMessage())
            .doesNotContain(token.jti())
            .doesNotContain(token.tokenHash());
      }
    } finally {
      appender.detach();
    }
  }

  /** 연결을 얻는 순간 실패한다. Lettuce의 연결 실패·타임아웃이 호출부에 도달하는 모습과 같다. */
  private record FailingConnectionFactory(RuntimeException failure)
      implements RedisConnectionFactory {

    @Override
    public RedisConnection getConnection() {
      throw this.failure;
    }

    @Override
    public RedisClusterConnection getClusterConnection() {
      throw this.failure;
    }

    @Override
    public RedisSentinelConnection getSentinelConnection() {
      throw this.failure;
    }

    @Override
    public boolean getConvertPipelineAndTxResults() {
      return false;
    }

    @Override
    public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
      return ex instanceof DataAccessException dataAccessException ? dataAccessException : null;
    }
  }

  /**
   * {@code action-current} 읽기와 스크립트 결과를 각각 고정한다.
   *
   * <p>{@code opsForValue().get()}은 내부적으로 {@link RedisCallback}을 거치므로 그 경로만 가로채면 Redis 없이도 발급 흐름을
   * 그대로 돌릴 수 있다. 스크립트 결과는 호출 순서대로 꺼내 쓰고, 비면 마지막 값을 반복한다.
   */
  private static class ScriptedRedisTemplate extends StringRedisTemplate {

    private final String currentJti;

    /** {@code ArrayDeque}는 null을 담지 못한다. 스크립트가 null을 돌려주는 경우도 재현해야 하므로 목록과 색인을 쓴다. */
    private final List<String> replies;

    private final AtomicInteger nextReply = new AtomicInteger();
    private final AtomicInteger currentReads = new AtomicInteger();
    private final AtomicInteger scriptExecutions = new AtomicInteger();

    private RuntimeException scriptFailure;

    ScriptedRedisTemplate(String currentJti, String... replies) {
      super(new FailingConnectionFactory(new RedisConnectionFailureException("쓰이지 않는다")));
      this.currentJti = currentJti;
      this.replies = Arrays.asList(replies);
      afterPropertiesSet();
    }

    void failScriptWith(RuntimeException failure) {
      this.scriptFailure = failure;
    }

    int currentReads() {
      return this.currentReads.get();
    }

    int scriptExecutions() {
      return this.scriptExecutions.get();
    }

    /** {@code opsForValue().get()}이 타고 오는 자리. 이 어댑터는 발급에서 이 경로만 쓴다. */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(RedisCallback<T> action, boolean exposeConnection, boolean pipeline) {
      this.currentReads.incrementAndGet();
      return (T) this.currentJti;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
      this.scriptExecutions.incrementAndGet();
      if (this.scriptFailure != null) {
        throw this.scriptFailure;
      }
      if (this.replies.isEmpty()) {
        return null;
      }
      // 목록을 다 쓰면 마지막 값을 계속 돌려준다.
      int index = Math.min(this.nextReply.getAndIncrement(), this.replies.size() - 1);
      return (T) this.replies.get(index);
    }
  }

  /** 스크립트에 실제로 넘어간 KEYS·ARGV를 확인할 때 쓴다. */
  private static final class RecordingScriptedRedisTemplate extends ScriptedRedisTemplate {

    private List<String> lastKeys;
    private Object[] lastArgs;

    RecordingScriptedRedisTemplate(String currentJti, String... replies) {
      super(currentJti, replies);
    }

    List<String> lastKeys() {
      return this.lastKeys;
    }

    Object[] lastArgs() {
      return this.lastArgs;
    }

    @Override
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
      this.lastKeys = List.copyOf(keys);
      this.lastArgs = args.clone();
      return super.execute(script, keys, args);
    }
  }
}
