package com.itda.user.infrastructure;

import com.itda.user.application.port.out.RefreshFamilyIssueResult;
import com.itda.user.application.port.out.RefreshFamilyRevocationResult;
import com.itda.user.application.port.out.RefreshRotationResult;
import com.itda.user.application.port.out.RefreshTokenLookupResult;
import com.itda.user.application.port.out.RefreshTokenRecord;
import com.itda.user.application.port.out.RefreshTokenStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Refresh Token 상태 저장소 어댑터 (API 명세 3.6.1절, ERD 3.1.1절).
 *
 * <p>상태 확인과 전이 사이에 다른 요청이 끼어들지 못하게 세 연산을 Lua 스크립트로 처리한다. GET 뒤에 별도 SET·DEL을 수행하는 경쟁 조건은 계약이 명시적으로
 * 금지한다.
 *
 * <p>{@link StringRedisTemplate}만 쓴다. 기본 {@code RedisTemplate}은 JDK 직렬화를 써서 키·값이 사람이 읽을 수 없는 바이트가
 * 되고, 운영에서 다른 클라이언트로 상태를 들여다볼 수 없게 된다. 키·필드·인자는 모두 UTF-8 문자열이다.
 *
 * <p>{@code EVALSHA} 실패 시 {@code NOSCRIPT}를 감지해 {@code EVAL}로 되돌리는 처리는 Spring Data Redis의 {@code
 * DefaultScriptExecutor}가 이미 수행한다. 같은 로직을 다시 만들지 않는다.
 *
 * <p>Redis 예외는 원문·host·port·키를 남기지 않고 {@code UNAVAILABLE}로만 바꾼다. 명령이 실제로 적용됐는지 알 수 없는 경우도 같다 —
 * 계약(3.6.2절)은 결과가 불확실한 처리를 성공으로 단정하지 말라고 정한다.
 */
public class LuaRefreshTokenStore implements RefreshTokenStore {

  private static final String FIELD_USER_ID = "userId";
  private static final String FIELD_FAMILY_ID = "familyId";
  private static final String FIELD_TOKEN_HASH = "tokenHash";
  private static final String FIELD_EXPIRES_AT = "expiresAt";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_CURRENT_JTI = "currentJti";

  private static final String STATUS_ACTIVE = "ACTIVE";
  private static final String STATUS_ROTATED = "ROTATED";
  private static final String STATUS_REVOKED = "REVOKED";

  /**
   * 저장된 만료 시각은 canonical epoch seconds 10진수 문자열이다. 소수·부호·공백·선행 0이 섞인 값은 손상된 상태로 본다 — {@code
   * rotateRefresh} Lua와 같은 형식이다.
   */
  private static final Pattern EPOCH_SECONDS_PATTERN = Pattern.compile("^[1-9][0-9]{0,18}$");

  private static final String RESULT_OK = "OK";
  private static final String RESULT_ALREADY_EXISTS = "ALREADY_EXISTS";
  private static final String RESULT_NOT_FOUND = "NOT_FOUND";
  private static final String RESULT_HASH_MISMATCH = "HASH_MISMATCH";
  private static final String RESULT_REUSED = "REUSED";
  private static final String RESULT_FAMILY_REVOKED = "FAMILY_REVOKED";

  private static final Logger log = LoggerFactory.getLogger(LuaRefreshTokenStore.class);

  private final StringRedisTemplate redis;
  private final AuthRedisKeys keys;
  private final RedisScript<String> issueFamilyScript;
  private final RedisScript<String> rotateScript;
  private final RedisScript<String> revokeFamilyScript;

  public LuaRefreshTokenStore(
      StringRedisTemplate redis,
      AuthRedisKeys keys,
      RedisScript<String> issueFamilyScript,
      RedisScript<String> rotateScript,
      RedisScript<String> revokeFamilyScript) {
    this.redis = redis;
    this.keys = keys;
    this.issueFamilyScript = issueFamilyScript;
    this.rotateScript = rotateScript;
    this.revokeFamilyScript = revokeFamilyScript;
  }

  /**
   * 자원 보호용 사전 조회다. 원자적이지 않으며 정확성의 최종 근거가 아니다 — 회전은 {@code rotateRefresh}가 같은 조건을 다시 검사한다.
   *
   * <p>저장된 값이 검증된 JWT와 어긋나면 {@code MISMATCHED}다. 어긋난 상태를 ACTIVE로 받아들이면 손상되거나 조작된 기록을 정상 세션으로 인정하게
   * 된다.
   *
   * <p>판정 순서는 {@code rotateRefresh}와 같다. {@code tokenHash}를 먼저 보고, 그다음 {@code ROTATED}·{@code
   * REVOKED}를 판정한 뒤, 정상 후보에만 {@code expiresAt}을 검사한다. 순서가 어긋나면 저장값이 손상된 재사용 토큰이 {@code MISMATCHED}로
   * 읽히고, 호출하는 유스케이스가 회전을 건너뛰어 재사용 탐지와 계열 폐기까지 가지 못한다.
   *
   * <p>이 메서드는 어떤 상태도 바꾸지 않는다. 재사용 판정과 계열 폐기는 {@code rotateRefresh}의 책임이다. key의 실제 만료 시각({@code
   * EXPIRETIME}) 확인도 원자적 최종 검사인 그쪽에만 둔다.
   */
  @Override
  public RefreshTokenLookupResult lookup(RefreshTokenRecord submitted) {
    long userId = submitted.userId();
    Map<Object, Object> token;
    Map<Object, Object> family;
    try {
      token = this.redis.opsForHash().entries(this.keys.refreshToken(userId, submitted.jti()));
      family = this.redis.opsForHash().entries(this.keys.family(userId, submitted.familyId()));
    } catch (DataAccessException e) {
      return unavailable("lookup");
    }
    if (token.isEmpty()) {
      return RefreshTokenLookupResult.NOT_FOUND;
    }
    String tokenUserId = field(token, FIELD_USER_ID);
    String tokenFamilyId = field(token, FIELD_FAMILY_ID);
    String storedHash = field(token, FIELD_TOKEN_HASH);
    String tokenStatus = field(token, FIELD_STATUS);
    if (tokenUserId == null || tokenFamilyId == null || storedHash == null || tokenStatus == null) {
      return RefreshTokenLookupResult.MISMATCHED;
    }
    if (!Long.toString(userId).equals(tokenUserId) || !submitted.familyId().equals(tokenFamilyId)) {
      return RefreshTokenLookupResult.MISMATCHED;
    }
    // 토큰을 실제로 갖고 있는지부터 본다. 해시가 틀리면 상태와 만료를 볼 것도 없다.
    if (!storedHash.equals(submitted.tokenHash())) {
      return RefreshTokenLookupResult.HASH_MISMATCH;
    }
    // 재사용 상태는 저장값 손상에 가리지 않는다 — 호출자가 회전을 건너뛰면 계열 폐기까지 가지 못한다.
    if (STATUS_ROTATED.equals(tokenStatus)) {
      return RefreshTokenLookupResult.ROTATED;
    }
    if (STATUS_REVOKED.equals(tokenStatus)) {
      return RefreshTokenLookupResult.REVOKED;
    }
    if (!STATUS_ACTIVE.equals(tokenStatus)) {
      return RefreshTokenLookupResult.MISMATCHED;
    }
    // 정상 후보에만 적용한다. 만료 시각도 검증된 JWT가 말하는 값이라, 저장값이 다르면 같은 토큰의 기록이 아니다.
    String storedExpiresAt = field(token, FIELD_EXPIRES_AT);
    if (storedExpiresAt == null
        || !EPOCH_SECONDS_PATTERN.matcher(storedExpiresAt).matches()
        || !Long.toString(submitted.expiresAtEpochSecond()).equals(storedExpiresAt)) {
      return RefreshTokenLookupResult.MISMATCHED;
    }
    return lookupFamily(userId, submitted.jti(), family);
  }

  private RefreshTokenLookupResult lookupFamily(
      long userId, String jti, Map<Object, Object> family) {
    if (family.isEmpty()) {
      // 토큰은 있는데 계열이 없다. 결정적으로 어긋난 상태이므로 활성으로 보지 않는다.
      return RefreshTokenLookupResult.MISMATCHED;
    }
    String familyUserId = field(family, FIELD_USER_ID);
    String familyStatus = field(family, FIELD_STATUS);
    String currentJti = field(family, FIELD_CURRENT_JTI);
    if (familyUserId == null || familyStatus == null || currentJti == null) {
      return RefreshTokenLookupResult.MISMATCHED;
    }
    if (!Long.toString(userId).equals(familyUserId)) {
      return RefreshTokenLookupResult.MISMATCHED;
    }
    if (STATUS_REVOKED.equals(familyStatus)) {
      return RefreshTokenLookupResult.FAMILY_REVOKED;
    }
    if (!STATUS_ACTIVE.equals(familyStatus)) {
      return RefreshTokenLookupResult.MISMATCHED;
    }
    if (!currentJti.equals(jti)) {
      // ACTIVE지만 계열의 현재 토큰이 아니다. 재사용 판정과 계열 폐기는 회전 스크립트가 원자적으로 수행한다.
      return RefreshTokenLookupResult.MISMATCHED;
    }
    return RefreshTokenLookupResult.ACTIVE;
  }

  @Override
  public RefreshFamilyIssueResult issueFamily(RefreshTokenRecord firstToken) {
    requireFutureExpiry(firstToken.expiresAtEpochSecond());
    String expiresAt = Long.toString(firstToken.expiresAtEpochSecond());
    String result =
        execute(
            "issueRefreshFamily",
            this.issueFamilyScript,
            List.of(
                this.keys.refreshToken(firstToken.userId(), firstToken.jti()),
                this.keys.family(firstToken.userId(), firstToken.familyId()),
                this.keys.families(firstToken.userId())),
            Long.toString(firstToken.userId()),
            firstToken.familyId(),
            firstToken.jti(),
            firstToken.tokenHash(),
            expiresAt);
    if (result == null) {
      return RefreshFamilyIssueResult.UNAVAILABLE;
    }
    return switch (result) {
      case RESULT_OK -> RefreshFamilyIssueResult.ISSUED;
      case RESULT_ALREADY_EXISTS -> RefreshFamilyIssueResult.ALREADY_EXISTS;
      default -> unknownResult("issueRefreshFamily", RefreshFamilyIssueResult.UNAVAILABLE);
    };
  }

  @Override
  public RefreshRotationResult rotate(RefreshTokenRecord current, RefreshTokenRecord next) {
    if (current.userId() != next.userId() || !current.familyId().equals(next.familyId())) {
      throw new IllegalArgumentException("회전 전후 토큰의 사용자·계열이 같아야 합니다.");
    }
    if (next.expiresAtEpochSecond() < current.expiresAtEpochSecond()) {
      // 계열 key의 만료를 앞당기면 아직 수명이 남은 토큰의 재사용을 판별할 수 없게 된다. Redis까지 가기 전에 막는다.
      throw new IllegalArgumentException("새 토큰의 만료가 기존 토큰보다 앞설 수 없습니다.");
    }
    // 기존 토큰의 만료는 여기서 보지 않는다. JWT 검증과 Redis key 만료 사이에서 자연스럽게 지날 수 있고,
    // 그 최종 판정은 Lua의 expiresAt·EXPIRETIME 검사가 한다.
    requireFutureExpiry(next.expiresAtEpochSecond());
    String result =
        execute(
            "rotateRefresh",
            this.rotateScript,
            List.of(
                this.keys.refreshToken(current.userId(), current.jti()),
                this.keys.family(current.userId(), current.familyId()),
                this.keys.refreshToken(next.userId(), next.jti()),
                this.keys.families(current.userId())),
            Long.toString(current.userId()),
            current.familyId(),
            current.jti(),
            current.tokenHash(),
            Long.toString(current.expiresAtEpochSecond()),
            next.jti(),
            next.tokenHash(),
            Long.toString(next.expiresAtEpochSecond()));
    if (result == null) {
      return RefreshRotationResult.UNAVAILABLE;
    }
    return switch (result) {
      case RESULT_OK -> RefreshRotationResult.ROTATED;
      case RESULT_NOT_FOUND -> RefreshRotationResult.NOT_FOUND;
      case RESULT_HASH_MISMATCH -> RefreshRotationResult.HASH_MISMATCH;
      case RESULT_REUSED -> RefreshRotationResult.REUSED;
      case RESULT_FAMILY_REVOKED -> RefreshRotationResult.FAMILY_REVOKED;
      case RESULT_ALREADY_EXISTS -> RefreshRotationResult.ALREADY_EXISTS;
      default -> unknownResult("rotateRefresh", RefreshRotationResult.UNAVAILABLE);
    };
  }

  @Override
  public RefreshFamilyRevocationResult revokeFamily(long userId, String familyId) {
    RefreshTokenRecord.requireUserId(userId);
    RefreshTokenRecord.requireFamilyId(familyId);
    String result =
        execute(
            "revokeFamily", this.revokeFamilyScript, List.of(this.keys.family(userId, familyId)));
    if (result == null) {
      return RefreshFamilyRevocationResult.UNAVAILABLE;
    }
    return switch (result) {
      case RESULT_OK -> RefreshFamilyRevocationResult.REVOKED;
      case RESULT_NOT_FOUND -> RefreshFamilyRevocationResult.NOT_FOUND;
      default -> unknownResult("revokeFamily", RefreshFamilyRevocationResult.UNAVAILABLE);
    };
  }

  /**
   * 이미 지난 만료 시각을 Redis까지 보내지 않는다.
   *
   * <p>{@code EXPIREAT}는 과거 시각을 받으면 키를 지운다. 그러면 스크립트는 정상적으로 {@code 'OK'}를 돌려주는데 저장된 것은 아무것도 없어, 아무
   * 상태도 남기지 못한 처리를 성공으로 보고하게 된다 (API 명세 3.6.2절: 일부 성공을 정상 완료로 숨기지 않는다).
   *
   * <p>message에 시각·식별자·해시를 넣지 않는다.
   */
  private static void requireFutureExpiry(long expiresAtEpochSecond) {
    if (expiresAtEpochSecond <= Instant.now().getEpochSecond()) {
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
    log.warn("인증 상태 저장소 작업에 실패했습니다. store=refresh-token stage={}", stage);
  }

  private static RefreshTokenLookupResult unavailable(String stage) {
    logFailure(stage);
    return RefreshTokenLookupResult.UNAVAILABLE;
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
