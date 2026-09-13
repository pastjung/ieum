package com.itda.user.application.port.out;

import com.itda.user.domain.AccountActionPurpose;
import java.util.regex.Pattern;

/**
 * Redis에 등록·조회·소비할 계정 작업 토큰 한 건의 상태 (API 명세 3.6.1절 「Redis 키 표기」).
 *
 * <p>토큰 원문을 담지 않는다. Redis에는 {@code tokenHash}만 저장하며, 원문은 메일로 나가는 경로에만 존재한다.
 *
 * <p>식별자와 해시의 형식을 여기서 확정한다. 이 값들은 그대로 Redis 키 문자열이 되므로, 형식을 확인하지 않으면 잘못된 값이 임의의 키를 만들어 낸다. 검증 메서드를
 * 밖으로 노출하는 이유는 {@link IssuedActionToken}처럼 record를 거치지 않는 경로에서도 같은 규칙을 써야 하기 때문이다.
 */
public record ActionTokenRecord(
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

  public ActionTokenRecord {
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

  /** 해시와 식별자가 로그로 새지 않게 한다. */
  @Override
  public String toString() {
    return "ActionTokenRecord[REDACTED]";
  }
}
