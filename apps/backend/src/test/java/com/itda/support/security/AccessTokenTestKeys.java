package com.itda.support.security;

import com.itda.global.security.AccessJwtProperties;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 테스트용 Access 서명 key를 만든다.
 *
 * <p>저장소에 고정 key 문자열을 넣지 않는다. 테스트 fixture라도 한 번 커밋되면 "어디선가 쓰이는 key"가 되어 남는다. 매 호출마다 CSPRNG로 새로 만들면
 * 그런 값이 생기지 않는다.
 */
public final class AccessTokenTestKeys {

  public static final String ISSUER = "itda-backend-test";

  private static final SecureRandom RANDOM = new SecureRandom();

  private AccessTokenTestKeys() {}

  /** 계약 최소 길이(256비트)의 Base64URL key. */
  public static String randomKey() {
    return randomKey(32);
  }

  public static String randomKey(int bytes) {
    byte[] value = new byte[bytes];
    RANDOM.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  public static AccessJwtProperties properties(String activeKid) {
    return new AccessJwtProperties(ISSUER, activeKid, randomKey(), "");
  }
}
