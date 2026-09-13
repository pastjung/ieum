package com.itda.user.infrastructure;

import com.itda.user.application.port.out.ActionTokenConsumeResult;
import com.itda.user.application.port.out.ActionTokenIssueResult;
import com.itda.user.application.port.out.ActionTokenLookupResult;
import com.itda.user.application.port.out.ActionTokenRecord;
import com.itda.user.application.port.out.ActionTokenStore;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 계정 작업 토큰 상태 저장소 어댑터 (API 명세 3.6.1절, ERD 3.1.1절).
 *
 * <p>적격 조건 확인과 전이 사이에 다른 요청이 끼어들지 못하게 발급·소비를 Lua 스크립트로 처리한다. 조회만 하는 {@code lookup}은 상태를 바꾸지 않으므로
 * 스크립트 없이 읽는다.
 *
 * <p>{@link StringRedisTemplate}만 쓴다. 기본 {@code RedisTemplate}은 JDK 직렬화를 써서 키·값이 사람이 읽을 수 없는 바이트가
 * 되고, 운영에서 다른 클라이언트로 상태를 들여다볼 수 없게 된다.
 *
 * <p>{@code EVALSHA} 실패 시 {@code NOSCRIPT}를 감지해 {@code EVAL}로 되돌리는 처리는 Spring Data Redis의 {@code
 * DefaultScriptExecutor}가 이미 수행한다. 연결 오류는 재실행 없이 전파되므로 이중 소비가 생기지 않는다.
 *
 * <p>Redis 예외는 원문·host·port·키를 남기지 않고 {@code UNAVAILABLE}로만 바꾼다. 명령이 실제로 적용됐는지 알 수 없는 경우도 같다 —
 * 계약(3.6.2절)은 결과가 불확실한 처리를 성공으로 단정하지 말라고 정한다.
 */
public class LuaActionTokenStore implements ActionTokenStore {

  private static final String FIELD_USER_ID = "userId";
  private static final String FIELD_PURPOSE = "purpose";
  private static final String FIELD_TOKEN_HASH = "tokenHash";
  private static final String FIELD_EXPIRES_AT = "expiresAt";
  private static final String FIELD_STATUS = "status";

  private static final String STATUS_ACTIVE = "ACTIVE";
  private static final String STATUS_CONSUMED = "CONSUMED";
  private static final String STATUS_INVALIDATED = "INVALIDATED";

  /**
   * {@code action-current} compare-and-set이 밀렸을 때 다시 시도하는 횟수.
   *
   * <p>경쟁자는 매번 하나씩 줄어들고 실제 동시 발급은 사용자 한 명·용도 하나에서 일어나므로 작은 상한으로 충분하다. 무한 재시도는 저장소가 이상할 때 요청 스레드를 묶어
   * 둔다.
   */
  private static final int MAX_CONTENTION_ATTEMPTS = 3;

  private static final String RESULT_OK = "OK";
  private static final String RESULT_ALREADY_EXISTS = "ALREADY_EXISTS";

  /** 발급 스크립트의 adapter 전용 결과. {@link ActionTokenStore}의 공개 결과로 내보내지 않는다. */
  private static final String RESULT_CONTENTION = "CONTENTION";

  private static final String RESULT_NOT_FOUND = "NOT_FOUND";
  private static final String RESULT_NOT_CURRENT = "NOT_CURRENT";
  private static final String RESULT_HASH_MISMATCH = "HASH_MISMATCH";
  private static final String RESULT_ALREADY_CONSUMED = "ALREADY_CONSUMED";
  private static final String RESULT_INVALIDATED = "INVALIDATED";

  /** 저장된 만료 시각은 canonical epoch seconds 10진수 문자열이다. Lua의 검사와 같은 형식이다. */
  private static final Pattern EPOCH_SECONDS_PATTERN = Pattern.compile("^[1-9][0-9]{0,18}$");

  private static final Logger log = LoggerFactory.getLogger(LuaActionTokenStore.class);

  private final StringRedisTemplate redis;
  private final AuthRedisKeys keys;
  private final RedisScript<String> issueActionTokenScript;
  private final RedisScript<String> consumeActionTokenScript;

  /** 만료 경계 판정에 쓰는 시계. 주입받아야 테스트가 실제 시스템 시간에 의존하지 않는다. */
  private final Clock clock;

  public LuaActionTokenStore(
      StringRedisTemplate redis,
      AuthRedisKeys keys,
      RedisScript<String> issueActionTokenScript,
      RedisScript<String> consumeActionTokenScript,
      Clock clock) {
    if (clock == null) {
      throw new IllegalArgumentException("시계가 없습니다.");
    }
    this.redis = redis;
    this.keys = keys;
    this.issueActionTokenScript = issueActionTokenScript;
    this.consumeActionTokenScript = consumeActionTokenScript;
    this.clock = clock;
  }

  /**
   * 이전 활성 토큰의 키는 스크립트가 조립할 수 없으므로(계약: 접근 키는 모두 {@code KEYS}로 전달) 여기서 {@code action-current}를 먼저 읽어
   * 넘긴다. 읽은 시각과 쓰는 시각 사이에 다른 발급이 끼어들 수 있으므로, 스크립트가 쓰기 전에 {@code action-current}가 읽은 값 그대로인지 확인하고
   * 달라졌으면 아무것도 바꾸지 않은 채 {@code CONTENTION}을 돌려준다. 여기서 다시 읽어 유한 횟수만큼 재시도한다.
   *
   * <p>이 재시도가 없으면 동시 발급에서 나중 요청이 이전 토큰을 무효화하지 못해, {@code action-current}가 가리키지 않는데 상태만 {@code
   * ACTIVE}로 남은 토큰이 생긴다. 소비는 {@code NOT_CURRENT}로 막히지만 저장 상태가 "이전 활성 토큰을 INVALIDATED로 전이한다"는 계약과
   * 어긋난다.
   *
   * <p>{@code CONTENTION}은 adapter 안에서만 쓰는 값이다. 상한에 닿으면 성공으로 단정하지 않고 {@link
   * ActionTokenIssueResult#UNAVAILABLE}로 끝낸다.
   */
  @Override
  public ActionTokenIssueResult issueActionToken(ActionTokenRecord token) {
    requireFutureExpiry(token.expiresAtEpochSecond());
    String tokenKey = this.keys.actionToken(token.userId(), token.purpose(), token.jti());
    String currentKey = this.keys.actionCurrent(token.userId(), token.purpose());
    for (int attempt = 1; attempt <= MAX_CONTENTION_ATTEMPTS; attempt++) {
      String previousJti;
      try {
        previousJti = this.redis.opsForValue().get(currentKey);
      } catch (DataAccessException e) {
        logFailure("issueActionToken-current");
        return ActionTokenIssueResult.UNAVAILABLE;
      }
      // 이전 토큰이 없으면 KEYS 개수를 맞추기 위해 신규 키를 넘기고, 빈 기대값으로 "없음"을 요구한다.
      String previousTokenKey =
          previousJti == null
              ? tokenKey
              : this.keys.actionToken(token.userId(), token.purpose(), previousJti);
      String result =
          execute(
              "issueActionToken",
              this.issueActionTokenScript,
              List.of(tokenKey, currentKey, previousTokenKey),
              Long.toString(token.userId()),
              token.purpose().name(),
              token.jti(),
              token.tokenHash(),
              Long.toString(token.expiresAtEpochSecond()),
              previousJti == null ? "" : previousJti);
      if (result == null) {
        return ActionTokenIssueResult.UNAVAILABLE;
      }
      switch (result) {
        case RESULT_OK:
          return ActionTokenIssueResult.ISSUED;
        case RESULT_ALREADY_EXISTS:
          return ActionTokenIssueResult.ALREADY_EXISTS;
        case RESULT_CONTENTION:
          // 다른 발급이 먼저 끝났다. 최신 action-current를 다시 읽어 같은 jti로 다시 시도한다.
          continue;
        default:
          return unknownResult("issueActionToken", ActionTokenIssueResult.UNAVAILABLE);
      }
    }
    // 상한까지 경쟁에서 밀렸다. 저장했는지 확정할 수 없으므로 성공으로 단정하지 않는다 (API 명세 3.6.2절).
    logFailure("issueActionToken-contention");
    return ActionTokenIssueResult.UNAVAILABLE;
  }

  /**
   * 자원 보호용 사전 조회다. 원자적이지 않으며 정확성의 최종 근거가 아니다 — 소비는 {@code consumeActionToken}이 같은 조건을 다시 검사한다.
   *
   * <p>판정 <b>순서</b>는 소비 스크립트와 같다. {@code tokenHash}를 먼저 보고, 그다음 상태를, 마지막으로 {@code action-current}
   * 일치와 저장된 만료를 본다.
   *
   * <p>다만 만료 판정의 <b>깊이</b>는 다르다. 소비 스크립트는 저장된 {@code expiresAt} 문자열에 더해 키의 {@code EXPIRETIME}까지
   * 확인하지만 여기서는 문자열만 본다. 그래서 TTL이 외부에서 바뀐 토큰을 이 조회는 {@code ACTIVE}로, 소비는 {@code NOT_FOUND}로 판정할 수
   * 있다. 사전 조회가 더 관대한 쪽이라 fail-closed는 유지되며, 정확성의 근거는 언제나 소비 쪽이다.
   */
  @Override
  public ActionTokenLookupResult lookup(ActionTokenRecord submitted) {
    String tokenKey =
        this.keys.actionToken(submitted.userId(), submitted.purpose(), submitted.jti());
    String currentKey = this.keys.actionCurrent(submitted.userId(), submitted.purpose());
    Map<Object, Object> token;
    String currentJti;
    try {
      token = this.redis.opsForHash().entries(tokenKey);
      currentJti = this.redis.opsForValue().get(currentKey);
    } catch (DataAccessException e) {
      logFailure("lookup");
      return ActionTokenLookupResult.UNAVAILABLE;
    }
    if (token.isEmpty()) {
      return ActionTokenLookupResult.NOT_FOUND;
    }
    String storedUserId = field(token, FIELD_USER_ID);
    String storedPurpose = field(token, FIELD_PURPOSE);
    String storedHash = field(token, FIELD_TOKEN_HASH);
    String storedStatus = field(token, FIELD_STATUS);
    if (storedUserId == null
        || storedPurpose == null
        || storedHash == null
        || storedStatus == null) {
      return ActionTokenLookupResult.MISMATCHED;
    }
    if (!Long.toString(submitted.userId()).equals(storedUserId)
        || !submitted.purpose().name().equals(storedPurpose)) {
      return ActionTokenLookupResult.MISMATCHED;
    }
    if (!storedHash.equals(submitted.tokenHash())) {
      return ActionTokenLookupResult.HASH_MISMATCH;
    }
    if (STATUS_CONSUMED.equals(storedStatus)) {
      return ActionTokenLookupResult.CONSUMED;
    }
    if (STATUS_INVALIDATED.equals(storedStatus)) {
      return ActionTokenLookupResult.INVALIDATED;
    }
    if (!STATUS_ACTIVE.equals(storedStatus)) {
      return ActionTokenLookupResult.MISMATCHED;
    }
    if (!submitted.jti().equals(currentJti)) {
      return ActionTokenLookupResult.NOT_CURRENT;
    }
    String storedExpiresAt = field(token, FIELD_EXPIRES_AT);
    if (storedExpiresAt == null
        || !EPOCH_SECONDS_PATTERN.matcher(storedExpiresAt).matches()
        || !Long.toString(submitted.expiresAtEpochSecond()).equals(storedExpiresAt)) {
      return ActionTokenLookupResult.MISMATCHED;
    }
    return ActionTokenLookupResult.ACTIVE;
  }

  @Override
  public ActionTokenConsumeResult consume(ActionTokenRecord submitted) {
    String result =
        execute(
            "consumeActionToken",
            this.consumeActionTokenScript,
            List.of(
                this.keys.actionToken(submitted.userId(), submitted.purpose(), submitted.jti()),
                this.keys.actionCurrent(submitted.userId(), submitted.purpose())),
            Long.toString(submitted.userId()),
            submitted.purpose().name(),
            submitted.jti(),
            submitted.tokenHash(),
            Long.toString(submitted.expiresAtEpochSecond()));
    if (result == null) {
      return ActionTokenConsumeResult.UNAVAILABLE;
    }
    return switch (result) {
      case RESULT_OK -> ActionTokenConsumeResult.CONSUMED;
      case RESULT_NOT_FOUND -> ActionTokenConsumeResult.NOT_FOUND;
      case RESULT_NOT_CURRENT -> ActionTokenConsumeResult.NOT_CURRENT;
      case RESULT_HASH_MISMATCH -> ActionTokenConsumeResult.HASH_MISMATCH;
      case RESULT_ALREADY_CONSUMED -> ActionTokenConsumeResult.ALREADY_CONSUMED;
      case RESULT_INVALIDATED -> ActionTokenConsumeResult.INVALIDATED;
      default -> unknownResult("consumeActionToken", ActionTokenConsumeResult.UNAVAILABLE);
    };
  }

  /**
   * 이미 지난 만료 시각을 Redis까지 보내지 않는다. {@code EXPIREAT}는 과거 시각을 받으면 키를 지우므로, 스크립트는 {@code 'OK'}를 돌려주는데
   * 저장된 것은 아무것도 없는 상태가 된다 (API 명세 3.6.2절: 일부 성공을 정상 완료로 숨기지 않는다).
   */
  private void requireFutureExpiry(long expiresAtEpochSecond) {
    if (expiresAtEpochSecond <= this.clock.instant().getEpochSecond()) {
      throw new IllegalArgumentException("저장할 만료 시각이 이미 지났습니다.");
    }
  }

  /** 실패를 {@code null}로 돌려준다. 호출부가 각자의 UNAVAILABLE로 바꾼다. */
  private String execute(
      String stage, RedisScript<String> script, List<String> keys, String... args) {
    try {
      return this.redis.execute(script, keys, (Object[]) args);
    } catch (DataAccessException e) {
      // 예외 객체·message·host·키를 남기지 않는다. 연결 실패·타임아웃·명령 실패가 모두 여기로 온다.
      logFailure(stage);
      return null;
    }
  }

  /** 어느 연산이 실패했는지만 남긴다. 키·해시·식별자·예외 원문은 넣지 않는다. */
  private static void logFailure(String stage) {
    log.warn("인증 상태 저장소 작업에 실패했습니다. store=action-token stage={}", stage);
  }

  /** 스크립트가 약속하지 않은 값을 돌려줬다. 성공으로 단정하지 않는다. */
  private static <T> T unknownResult(String stage, T unavailable) {
    logFailure(stage + "-unknown-result");
    return unavailable;
  }

  private static String field(Map<Object, Object> hash, String name) {
    Object value = hash.get(name);
    return value instanceof String text && !text.isEmpty() ? text : null;
  }
}
