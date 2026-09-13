package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.itda.support.log.CapturingAppender;
import com.itda.user.application.port.out.RefreshFamilyIssueResult;
import com.itda.user.application.port.out.RefreshFamilyRevocationResult;
import com.itda.user.application.port.out.RefreshRotationResult;
import com.itda.user.application.port.out.RefreshTokenLookupResult;
import com.itda.user.application.port.out.RefreshTokenRecord;
import com.itda.user.application.port.out.RefreshTokenStore;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 저장소 장애와 예상 밖 응답을 어떻게 다루는지 실제 Redis 없이 고정한다.
 *
 * <p>연결 실패·타임아웃을 재현하려고 진짜 네트워크 대기를 걸지 않는다. 1초짜리 타임아웃을 기다리는 테스트는 느리고 환경에 따라 흔들린다. 대신 Spring Data
 * Redis가 그 상황에서 던지는 예외를 그대로 던지게 해 변환 규칙만 검증한다.
 */
class LuaRefreshTokenStoreTest {

  private static final long USER_ID = 7L;

  private static final AuthRedisKeys KEYS = new AuthRedisKeys(new AuthRedisProperties("test"));

  /** 2100-01-01. 만료 경계 검증이 시계에 따라 흔들리지 않게 충분히 먼 미래를 쓴다. */
  private static final long FUTURE_EPOCH_SECOND = 4_102_444_800L;

  /** 2001-09-09. 현재보다 충분히 과거라 초 경계에서 판정이 바뀌지 않는다. */
  private static final long PAST_EPOCH_SECOND = 1_000_000_000L;

  private static String someHash() {
    return CompactTokenHasher.hash("header.payload." + UUID.randomUUID());
  }

  private static RefreshTokenRecord record(String familyId, String jti) {
    return new RefreshTokenRecord(USER_ID, familyId, jti, someHash(), FUTURE_EPOCH_SECOND);
  }

  /** 초 경계에서 흔들리지 않게 현재보다 충분히 과거인 값을 쓴다. */
  private static RefreshTokenRecord recordExpiringAt(long expiresAtEpochSecond) {
    return new RefreshTokenRecord(
        USER_ID,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        someHash(),
        expiresAtEpochSecond);
  }

  private static RefreshTokenStore storeOf(StringRedisTemplate template) {
    return new LuaRefreshTokenStore(
        template,
        KEYS,
        script("issue-refresh-family.lua"),
        script("rotate-refresh.lua"),
        script("revoke-family.lua"));
  }

  private static RedisScript<String> script(String fileName) {
    return RedisScript.of(new ClassPathResource("redis/" + fileName), String.class);
  }

  private static RefreshTokenStore failingStore(RuntimeException failure) {
    StringRedisTemplate template = new StringRedisTemplate(new FailingConnectionFactory(failure));
    template.afterPropertiesSet();
    return storeOf(template);
  }

  private static RefreshTokenStore replyingStore(String reply) {
    return storeOf(new FixedReplyRedisTemplate(reply));
  }

  @Test
  void 연결_실패는_모든_연산에서_UNAVAILABLE이다() {
    RefreshTokenStore store = failingStore(new RedisConnectionFailureException("연결 실패"));
    String familyId = UUID.randomUUID().toString();
    String jti = UUID.randomUUID().toString();

    assertThat(store.lookup(record(familyId, jti))).isEqualTo(RefreshTokenLookupResult.UNAVAILABLE);
    assertThat(store.issueFamily(record(familyId, jti)))
        .isEqualTo(RefreshFamilyIssueResult.UNAVAILABLE);
    assertThat(store.rotate(record(familyId, jti), record(familyId, UUID.randomUUID().toString())))
        .isEqualTo(RefreshRotationResult.UNAVAILABLE);
    assertThat(store.revokeFamily(USER_ID, familyId))
        .isEqualTo(RefreshFamilyRevocationResult.UNAVAILABLE);
  }

  @Test
  void 명령_타임아웃도_UNAVAILABLE이다() {
    RefreshTokenStore store = failingStore(new QueryTimeoutException("응답 없음"));

    assertThat(
            store.issueFamily(record(UUID.randomUUID().toString(), UUID.randomUUID().toString())))
        .isEqualTo(RefreshFamilyIssueResult.UNAVAILABLE);
  }

  @Test
  void 서버가_명령을_거부해도_UNAVAILABLE이다() {
    RefreshTokenStore store =
        failingStore(new RedisSystemException("실행 실패", new IllegalStateException()));

    assertThat(store.revokeFamily(USER_ID, UUID.randomUUID().toString()))
        .isEqualTo(RefreshFamilyRevocationResult.UNAVAILABLE);
  }

  @Test
  void 스크립트가_약속하지_않은_값을_주면_성공으로_단정하지_않는다() {
    String familyId = UUID.randomUUID().toString();
    String jti = UUID.randomUUID().toString();

    assertThat(replyingStore("WAT").issueFamily(record(familyId, jti)))
        .isEqualTo(RefreshFamilyIssueResult.UNAVAILABLE);
    assertThat(
            replyingStore(null)
                .rotate(record(familyId, jti), record(familyId, UUID.randomUUID().toString())))
        .isEqualTo(RefreshRotationResult.UNAVAILABLE);
    assertThat(replyingStore("").revokeFamily(USER_ID, familyId))
        .isEqualTo(RefreshFamilyRevocationResult.UNAVAILABLE);
  }

  @Test
  void 회전_전후의_계열이_다르면_요청_자체를_거부한다() {
    RefreshTokenStore store = replyingStore("OK");
    RefreshTokenRecord current = record(UUID.randomUUID().toString(), UUID.randomUUID().toString());
    RefreshTokenRecord otherFamily =
        record(UUID.randomUUID().toString(), UUID.randomUUID().toString());

    assertThatThrownBy(() -> store.rotate(current, otherFamily))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 새_토큰의_만료가_기존보다_이르면_Redis를_부르기_전에_거부한다() {
    RefreshTokenStore store = replyingStore("OK");
    String familyId = UUID.randomUUID().toString();
    RefreshTokenRecord current = record(familyId, UUID.randomUUID().toString());
    RefreshTokenRecord earlier =
        new RefreshTokenRecord(
            USER_ID,
            familyId,
            UUID.randomUUID().toString(),
            someHash(),
            current.expiresAtEpochSecond() - 1);

    assertThatThrownBy(() -> store.rotate(current, earlier))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 계약_형식이_아닌_계열_식별자로는_키를_만들지_않는다() {
    RefreshTokenStore store = replyingStore("OK");

    assertThatThrownBy(() -> store.revokeFamily(USER_ID, "../other-key"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> store.revokeFamily(USER_ID, UUID.randomUUID().toString().toUpperCase()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.revokeFamily(0, UUID.randomUUID().toString()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 이미_지난_만료로는_Redis를_부르지_않고_등록을_거부한다() {
    FixedReplyRedisTemplate template = new FixedReplyRedisTemplate("OK");
    RefreshTokenStore store = storeOf(template);
    RefreshTokenRecord expired = recordExpiringAt(PAST_EPOCH_SECOND);

    assertThatThrownBy(() -> store.issueFamily(expired))
        .isInstanceOf(IllegalArgumentException.class);
    // EXPIREAT는 과거 시각을 받으면 키를 지운다. 그러면 스크립트는 OK를 돌려주는데 저장된 것은 없다.
    assertThat(template.executionCount()).isZero();
  }

  @Test
  void 이미_지난_만료로는_Redis를_부르지_않고_회전을_거부한다() {
    FixedReplyRedisTemplate template = new FixedReplyRedisTemplate("OK");
    RefreshTokenStore store = storeOf(template);
    String familyId = UUID.randomUUID().toString();
    RefreshTokenRecord current =
        new RefreshTokenRecord(
            USER_ID, familyId, UUID.randomUUID().toString(), someHash(), PAST_EPOCH_SECOND - 1);
    RefreshTokenRecord next =
        new RefreshTokenRecord(
            USER_ID, familyId, UUID.randomUUID().toString(), someHash(), PAST_EPOCH_SECOND);

    assertThatThrownBy(() -> store.rotate(current, next))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(template.executionCount()).isZero();
  }

  @Test
  void 미래_만료는_기존대로_Redis까지_간다() {
    FixedReplyRedisTemplate template = new FixedReplyRedisTemplate("OK");
    RefreshTokenStore store = storeOf(template);
    String familyId = UUID.randomUUID().toString();

    assertThat(store.issueFamily(record(familyId, UUID.randomUUID().toString())))
        .isEqualTo(RefreshFamilyIssueResult.ISSUED);
    assertThat(
            store.rotate(
                record(familyId, UUID.randomUUID().toString()),
                record(familyId, UUID.randomUUID().toString())))
        .isEqualTo(RefreshRotationResult.ROTATED);
    assertThat(template.executionCount()).isEqualTo(2);
  }

  @Test
  void 만료_거부_message에_시각도_식별자도_해시도_없다() {
    RefreshTokenStore store = replyingStore("OK");
    RefreshTokenRecord expired = recordExpiringAt(PAST_EPOCH_SECOND);

    assertThatThrownBy(() -> store.issueFamily(expired))
        .hasMessageNotContaining(Long.toString(PAST_EPOCH_SECOND))
        .hasMessageNotContaining(expired.jti())
        .hasMessageNotContaining(expired.familyId())
        .hasMessageNotContaining(expired.tokenHash());
  }

  @Test
  void 장애_로그에_해시도_키도_예외도_남기지_않는다() {
    String familyId = UUID.randomUUID().toString();
    String jti = UUID.randomUUID().toString();
    RefreshTokenRecord first = record(familyId, jti);
    RefreshTokenStore store = failingStore(new RedisConnectionFailureException("연결 실패"));

    CapturingAppender appender = CapturingAppender.attachTo(LuaRefreshTokenStore.class);
    try {
      assertThat(store.issueFamily(first)).isEqualTo(RefreshFamilyIssueResult.UNAVAILABLE);

      List<ILoggingEvent> events = appender.events();
      assertThat(events).isNotEmpty();
      for (ILoggingEvent event : events) {
        assertThat(event.getFormattedMessage())
            .doesNotContain(first.tokenHash())
            .doesNotContain(jti)
            .doesNotContain(familyId)
            .doesNotContain(KEYS.refreshToken(USER_ID, jti))
            .doesNotContain("연결 실패");
        assertThat(event.getThrowableProxy()).isNull();
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

  /** 스크립트 실행 결과만 고정하고 호출 횟수를 센다. Redis에 닿기 전에 막혔는지 확인하는 데 쓴다. */
  private static final class FixedReplyRedisTemplate extends StringRedisTemplate {

    private final String reply;
    private final AtomicInteger executions = new AtomicInteger();

    private FixedReplyRedisTemplate(String reply) {
      super(new FailingConnectionFactory(new RedisConnectionFailureException("쓰이지 않는다")));
      this.reply = reply;
      afterPropertiesSet();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
      this.executions.incrementAndGet();
      return (T) this.reply;
    }

    int executionCount() {
      return this.executions.get();
    }
  }
}
