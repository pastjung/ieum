package com.itda.user.repository;

import java.util.regex.Pattern;

/**
 * Refresh Token의 Redis 상태 outbound port (API 명세 3.6.1절, ERD 3.1.1절).
 *
 * <p>네 연산을 한 interface에 두는 이유는 같은 저장소의 같은 키 집합을 다루기 때문이다. 호출자는 Redis 키 문자열·자료구조·Lua 반환 문자열을 전혀 보지
 * 않고 닫힌 결과만 받는다.
 *
 * <p>어떤 메서드도 토큰 원문을 받지 않는다. Redis에 남는 것은 {@code tokenHash}뿐이다.
 */
public interface RefreshTokenStore {

  /**
   * 회전 전 자원 보호용 사전 조회. <b>정확성의 최종 근거가 아니며 어떤 상태도 바꾸지 않는다.</b>
   *
   * <p>검증된 JWT가 말하는 값을 그대로 {@link TokenRecord}에 담아 넘긴다. {@code userId}·{@code familyId}·{@code
   * jti}·{@code expiresAtEpochSecond}가 저장된 상태와 하나라도 어긋나면 {@code MISMATCHED}이고, {@code tokenHash}만
   * 다르면 {@code HASH_MISMATCH}다.
   *
   * <p><b>호출 계약.</b> {@code MISMATCHED}에는 토큰이 {@code ACTIVE}지만 계열의 {@code currentJti}와 일치하지 않는 상태도
   * 포함된다. 같은 상태를 {@link #rotate}는 재사용으로 판정하고 같은 원자 연산에서 계열을 폐기하므로, 호출자는 {@code MISMATCHED}만으로 "재사용이
   * 아니다"라고 확정해서는 안 된다. 재사용 탐지와 계열 폐기가 필요한 갱신 흐름의 최종 판정은 {@link #rotate}의 원자적 결과를 따른다.
   *
   * @param submitted 제출된 토큰의 검증된 metadata와 해시
   */
  LookupResult lookup(TokenRecord submitted);

  /**
   * 새 계열과 첫 토큰을 등록한다. 이미 있는 식별자를 덮어쓰지 않는다.
   *
   * @param firstToken 만료 시각이 현재보다 뒤여야 한다
   * @throws IllegalArgumentException 만료 시각이 이미 지났을 때. 과거 시각으로 저장하면 Redis가 키를 즉시 지워, 아무 상태도 남기지 못한
   *     처리가 성공으로 보고된다
   */
  FamilyIssueResult issueFamily(TokenRecord firstToken);

  /**
   * 기존 토큰을 회전한다. 전제 조건 검사와 상태 전이가 하나의 원자 연산 안에서 일어난다.
   *
   * @param current 제출된 토큰. {@code tokenHash}로 소지 여부를, {@code expiresAtEpochSecond}로 저장된 상태와의 일치를
   *     확인한다
   * @param next 새로 발급한 토큰. {@code familyId}는 {@code current}와 같아야 하고 만료는 {@code current}보다 앞설 수 없다
   *     — 계열이 아직 유효한 토큰보다 먼저 만료되면 재사용을 판별할 수 없다. 만료 시각은 현재보다 뒤여야 한다
   * @throws IllegalArgumentException 두 토큰의 사용자나 계열이 다를 때, {@code next}의 만료가 {@code current}보다 앞설 때,
   *     또는 {@code next}의 만료가 이미 지났을 때. {@code current}의 만료는 여기서 보지 않는다 — 검증과 저장소 만료 사이에서 자연스럽게 지날 수
   *     있어 최종 판정은 원자 연산이 한다
   */
  RotationResult rotate(TokenRecord current, TokenRecord next);

  /**
   * 계열을 폐기한다. 계열 hash의 상태만 바꾸고 토큰 상태는 바꾸지 않는다. 같은 계열에 여러 번 호출해도 결과가 같다.
   *
   * @throws IllegalArgumentException {@code userId}가 양수가 아니거나 {@code familyId}가 canonical UUID v4가
   *     아닐 때. 확인하지 않으면 잘못된 값이 그대로 저장소 키가 된다
   */
  FamilyRevocationResult revokeFamily(long userId, String familyId);

  /**
   * Redis에 등록·회전하거나 조회할 Refresh Token 한 건의 상태 (API 명세 3.6.1절 「Redis 키 표기」).
   *
   * <p>토큰 원문을 담지 않는다. Redis에는 {@code tokenHash}만 저장하며, 원문은 클라이언트에게 전달하는 경로에만 존재한다.
   *
   * <p>{@code userId}를 문자열이 아니라 {@code long}으로 받되 키 조립과 Lua 인자에서는 10진수 문자열로만 다룬다 — BIGINT를 Lua
   * number로 넘기면 정밀도를 잃는다.
   *
   * <p>식별자와 해시의 형식을 여기서 확정한다. 이 값들은 그대로 Redis 키 문자열이 되므로, 형식을 확인하지 않으면 잘못된 값이 임의의 키를 만들어 낸다. 검증
   * 메서드를 밖으로 노출하는 이유는 {@link RefreshTokenStore#revokeFamily}처럼 record를 거치지 않는 인자 경로에서도 같은 규칙을 써야 하기
   * 때문이다.
   */
  record TokenRecord(
      long userId, String familyId, String jti, String tokenHash, long expiresAtEpochSecond) {

    /** canonical UUID v4. 대문자 표기를 허용하지 않는다 — 같은 값이 두 가지 키가 되면 상태가 갈라진다. */
    private static final Pattern UUID_V4_PATTERN =
        Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    /** SHA-256을 Base64URL(padding 없음)로 인코딩한 값: 32바이트 → 정확히 43자. */
    private static final Pattern TOKEN_HASH_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    public TokenRecord {
      requireUserId(userId);
      requireFamilyId(familyId);
      requireJti(jti);
      requireTokenHash(tokenHash);
      if (expiresAtEpochSecond <= 0) {
        throw new IllegalArgumentException("만료 시각이 올바르지 않습니다.");
      }
    }

    /**
     * 예외 message에 값을 넣지 않는다 — 식별자와 해시가 설정 오류 로그로 옮겨 갈 이유가 없다.
     *
     * <p>저장소 구현만 쓰므로 패키지 밖에 노출하지 않는다.
     */
    static long requireUserId(long userId) {
      if (userId <= 0) {
        throw new IllegalArgumentException("사용자 식별자가 올바르지 않습니다.");
      }
      return userId;
    }

    public static String requireFamilyId(String familyId) {
      return requireUuidV4(familyId, "계열 식별자");
    }

    public static String requireJti(String jti) {
      return requireUuidV4(jti, "토큰 식별자");
    }

    public static String requireTokenHash(String tokenHash) {
      if (tokenHash == null || !TOKEN_HASH_PATTERN.matcher(tokenHash).matches()) {
        throw new IllegalArgumentException("토큰 해시 형식이 올바르지 않습니다.");
      }
      return tokenHash;
    }

    private static String requireUuidV4(String value, String name) {
      if (value == null || !UUID_V4_PATTERN.matcher(value).matches()) {
        throw new IllegalArgumentException(name + "가 canonical UUID v4 형식이 아닙니다.");
      }
      return value;
    }

    /** 해시와 식별자가 로그로 새지 않게 한다. 출력 문자열은 통합 전 top-level 타입 이름을 유지한다. */
    @Override
    public String toString() {
      return "RefreshTokenRecord[REDACTED]";
    }
  }

  /**
   * 회전 전 Refresh 상태 사전 조회 결과.
   *
   * <p>이 조회는 <b>자원 보호용</b>이다. 서명만 정상인 토큰이 반복 제출될 때 값비싼 회전 경로까지 가지 않게 막을 뿐이며, 정확성의 최종 근거가 아니다. 실제
   * 전이는 {@code rotateRefresh} Lua가 같은 조건을 원자적으로 다시 검사하면서 수행한다 (API 명세 3.4.2·3.6.1절).
   *
   * <p>이 조회는 어떤 상태도 바꾸지 않는다. 재사용 탐지와 계열 폐기는 {@code rotateRefresh}만 수행한다.
   */
  enum LookupResult {
    ACTIVE,
    NOT_FOUND,
    ROTATED,
    REVOKED,
    FAMILY_REVOKED,
    HASH_MISMATCH,
    /**
     * Redis에 저장된 식별자·필드가 검증된 JWT와 어긋난 경우다. 저장 상태가 결정적으로 어긋났는데 ACTIVE로 받아들이면 조작되거나 손상된 상태를 정상 세션으로
     * 인정하게 된다.
     *
     * <p><b>재사용이 아님을 확정하는 값이 아니다.</b> 토큰이 {@code ACTIVE}지만 계열의 {@code currentJti}와 일치하지 않는 상태가 여기에
     * 포함되는데, 같은 상태를 {@code rotateRefresh}는 재사용으로 판정하고 계열을 폐기한다. 이 값만 보고 회전을 건너뛰면 재사용 탐지와 계열 폐기까지 가지
     * 못한다.
     */
    MISMATCHED,
    /** 상태를 읽지 못했거나 결과가 불확실하다. 인증 실패로 단정하지 않는다 (API 명세 3.6.2절 fail-closed). */
    UNAVAILABLE
  }

  /**
   * Refresh 원자 회전 결과 ({@code rotateRefresh}).
   *
   * <p>{@code REUSED}는 이미 회전·폐기된 토큰이 다시 제출됐거나, ACTIVE 토큰이지만 계열의 현재 토큰이 아닌 경우다. 두 경우 모두 같은 스크립트 안에서
   * 계열을 폐기한 뒤 이 값을 돌려준다 — 탐지와 폐기 사이에 다른 요청이 끼어들지 못하게 하기 위해서다.
   *
   * <p>{@code ALREADY_EXISTS}는 신규 {@code jti} 키가 이미 있어 아무 상태도 바꾸지 않은 경우다. 새 식별자로 다시 시도할지는 호출하는
   * 유스케이스가 정한다.
   *
   * <p>이 값들은 내부 판정용이다. HTTP 응답 body·헤더에 노출하지 않는다 (API 명세 3.6.1절 「내부 사유의 비노출」).
   */
  enum RotationResult {
    ROTATED,
    NOT_FOUND,
    HASH_MISMATCH,
    REUSED,
    FAMILY_REVOKED,
    ALREADY_EXISTS,
    UNAVAILABLE
  }

  /**
   * 새 계열과 첫 Refresh 등록 결과 ({@code issueRefreshFamily}).
   *
   * <p>{@code ALREADY_EXISTS}는 같은 {@code familyId} 또는 {@code jti}가 이미 있어 덮어쓰기를 거부한 경우다. 새 식별자를 만들어
   * 다시 시도하는 책임은 이 port가 아니라 호출하는 유스케이스에 있다.
   */
  enum FamilyIssueResult {
    ISSUED,
    ALREADY_EXISTS,
    UNAVAILABLE
  }

  /** 계열 폐기 결과 ({@code revokeFamily}). 계열 hash의 상태만 바꾸며 토큰 상태는 건드리지 않는다. */
  enum FamilyRevocationResult {
    REVOKED,
    NOT_FOUND,
    UNAVAILABLE
  }
}
