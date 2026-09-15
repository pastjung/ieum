package com.itda.user.service;

import com.itda.support.security.AccessTokenTestKeys;

/**
 * 테스트용 계정 작업 토큰 서명 key를 만든다.
 *
 * <p>key 생성은 {@link AccessTokenTestKeys}와 같은 CSPRNG를 쓴다. 저장소에 고정 key 문자열을 넣지 않기 위해서다. issuer는
 * Access 설정에서 오므로 여기서 만들지 않는다.
 */
public final class ActionTokenTestKeys {

  private ActionTokenTestKeys() {}

  public static String randomKey() {
    return AccessTokenTestKeys.randomKey();
  }

  public static String randomKey(int bytes) {
    return AccessTokenTestKeys.randomKey(bytes);
  }

  public static ActionJwtProperties properties(String activeKid) {
    return new ActionJwtProperties(activeKid, randomKey(), "");
  }
}
