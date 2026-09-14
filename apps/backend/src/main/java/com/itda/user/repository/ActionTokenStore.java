package com.itda.user.repository;

import com.itda.user.domain.AccountActionPurpose;
import java.util.regex.Pattern;

/**
 * 계정 작업 토큰의 Redis 상태 outbound port (API 명세 3.6.1절, ERD 3.1.1절).
 *
 * <p>세 연산을 한 interface에 두는 이유는 같은 저장소의 같은 키 집합을 다루기 때문이다. 호출자는 Redis 키 문자열·자료구조·Lua 반환 문자열을 전혀 보지
 * 않고 닫힌 결과만 받는다.
 *
 * <p>어떤 메서드도 토큰 원문을 받지 않는다. Redis에 남는 것은 {@code tokenHash}뿐이다.
 */
public interface ActionTokenStore {

  /**
   * 새 토큰을 등록하고 같은 사용자·용도의 이전 활성 토큰을 무효화한다. 이미 있는 {@code jti}는 덮어쓰지 않는다.
   *
   * @param token 만료 시각이 현재보다 뒤여야 한다
   * @throws IllegalArgumentException 만료 시각이 이미 지났을 때. 과거 시각으로 저장하면 Redis가 키를 즉시 지워, 아무 상태도 남기지 못한
   *     처리가 성공으로 보고된다
   */
  IssueResult issueActionToken(TokenRecord token);

  /**
   * 소비 전 자원 보호용 사전 조회. <b>정확성의 최종 근거가 아니며 어떤 상태도 바꾸지 않는다.</b>
   *
   * <p>호출자는 이 결과만으로 소비 가능 여부를 확정하지 않는다. 사전 조회와 소비 사이에 상태가 바뀌면 최종 소비가 실패해야 한다는 것이 계약(3.4.2절)이다.
   *
   * @throws IllegalArgumentException 식별자 형식이 계약과 다를 때
   */
  LookupResult lookup(TokenRecord submitted);

  /**
   * 토큰을 원자적으로 소비한다. 적격 조건 확인과 {@code CONSUMED} 전이, {@code action-current} 정리가 한 연산에서 일어난다.
   *
   * @throws IllegalArgumentException 식별자 형식이 계약과 다를 때
   */
  ConsumeResult consume(TokenRecord submitted);

  /**
   * Redis에 등록·조회·소비할 계정 작업 토큰 한 건의 상태 (API 명세 3.6.1절 「Redis 키 표기」).
   *
   * <p>토큰 원문을 담지 않는다. Redis에는 {@code tokenHash}만 저장하며, 원문은 메일로 나가는 경로에만 존재한다.
   *
   * <p>식별자와 해시의 형식을 여기서 확정한다. 이 값들은 그대로 Redis 키 문자열이 되므로, 형식을 확인하지 않으면 잘못된 값이 임의의 키를 만들어 낸다. 검증
   * 메서드를 밖으로 노출하는 이유는 {@code IssuedActionToken}처럼 record를 거치지 않는 경로에서도 같은 규칙을 써야 하기 때문이다.
   */
  record TokenRecord(
      long userId,
      AccountActionPurpose purpose,
      String jti,
      String tokenHash,
      long expiresAtEpochSecond) {

    /** canonical UUID v4. 대문자 표기를 허용하지 않는다 — 같은 값이 두 가지 키가 되면 상태가 갈라진다. */
    private static final Pattern UUID_V4_PATTERN =
        Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    /** SHA-256을 Base64URL(padding 없음)로 인코딩한 값: 32바이트 → 정확히 43자. */
    private static final Pattern TOKEN_HASH_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    public TokenRecord {
      requireUserId(userId);
      requirePurpose(purpose);
      requireJti(jti);
      requireTokenHash(tokenHash);
      if (expiresAtEpochSecond <= 0) {
        throw new IllegalArgumentException("만료 시각이 올바르지 않습니다.");
      }
    }

    /** 예외 message에 값을 넣지 않는다 — 식별자와 해시가 설정 오류 로그로 옮겨 갈 이유가 없다. */
    public static long requireUserId(long userId) {
      if (userId <= 0) {
        throw new IllegalArgumentException("사용자 식별자가 올바르지 않습니다.");
      }
      return userId;
    }

    public static AccountActionPurpose requirePurpose(AccountActionPurpose purpose) {
      if (purpose == null) {
        throw new IllegalArgumentException("계정 작업 용도가 없습니다.");
      }
      return purpose;
    }

    public static String requireJti(String jti) {
      if (jti == null || !UUID_V4_PATTERN.matcher(jti).matches()) {
        throw new IllegalArgumentException("토큰 식별자가 canonical UUID v4 형식이 아닙니다.");
      }
      return jti;
    }

    public static String requireTokenHash(String tokenHash) {
      if (tokenHash == null || !TOKEN_HASH_PATTERN.matcher(tokenHash).matches()) {
        throw new IllegalArgumentException("토큰 해시 형식이 올바르지 않습니다.");
      }
      return tokenHash;
    }

    /** 해시와 식별자가 로그로 새지 않게 한다. 출력 문자열은 통합 전 top-level 타입 이름을 유지한다. */
    @Override
    public String toString() {
      return "ActionTokenRecord[REDACTED]";
    }
  }

  /**
   * 계정 작업 토큰 등록 결과 ({@code issueActionToken}, API 명세 3.6.1절).
   *
   * <p>{@code ALREADY_EXISTS}는 같은 {@code jti}가 이미 있어 덮어쓰기를 거부한 경우다. 새 UUID를 만들어 유한 횟수로 다시 시도하는 책임은
   * 이 port가 아니라 호출하는 유스케이스에 있다.
   */
  enum IssueResult {
    ISSUED,
    ALREADY_EXISTS,
    /** 상태를 바꾸지 못했거나 결과가 불확실하다. 성공으로 단정하지 않는다 (API 명세 3.6.2절 fail-closed). */
    UNAVAILABLE
  }

  /**
   * 소비 전 계정 작업 토큰 상태 사전 조회 결과.
   *
   * <p>이 조회는 <b>자원 보호용</b>이다. 값비싼 검사(비밀번호 정책·Argon2id)를 반복 유발하지 못하게 막을 뿐이며 정확성의 최종 근거가 아니다(API 명세
   * 3.4.2절). 실제 전이는 {@code consumeActionToken}이 같은 조건을 원자적으로 다시 검사하면서 수행한다.
   *
   * <p>이 조회는 어떤 상태도 바꾸지 않는다.
   */
  enum LookupResult {
    ACTIVE,
    NOT_FOUND,
    /** {@code action-current}가 이 {@code jti}를 가리키지 않는다. 재발급으로 밀려난 토큰이다. */
    NOT_CURRENT,
    CONSUMED,
    INVALIDATED,
    HASH_MISMATCH,
    /** 저장된 식별자·필드가 검증된 JWT와 어긋난다. 손상되거나 조작된 기록을 정상으로 인정하지 않는다. */
    MISMATCHED,
    UNAVAILABLE
  }

  /**
   * 계정 작업 토큰 원자적 소비 결과 ({@code consumeActionToken}, API 명세 3.6.1절).
   *
   * <p>{@code action-current}가 요청 {@code jti}를 가리키는지, 상태가 {@code ACTIVE}인지, {@code tokenHash}가
   * 일치하는지를 한 연산에서 확인하고 {@code CONSUMED}로 전이한다. 전이에 성공하면 {@code action-current}도 같은 연산에서 정리한다.
   *
   * <p>이 값들은 내부 판정용이다. 계약(3.4.1·3.4.2절)은 불일치 결과를 모두 {@code 400 ACTION_TOKEN_INVALID}로 일반화하므로 HTTP
   * 응답에 그대로 노출하지 않는다.
   */
  enum ConsumeResult {
    CONSUMED,
    NOT_FOUND,
    NOT_CURRENT,
    HASH_MISMATCH,
    ALREADY_CONSUMED,
    INVALIDATED,
    UNAVAILABLE
  }
}
