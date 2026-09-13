package com.itda.user.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Refresh ring의 서명 key 설정 (API 명세 3.6.1절).
 *
 * <p>{@code issuer}를 두지 않는다. 세 종류의 토큰이 같은 발급자를 쓰므로 환경변수를 하나만 유지하고, 값은 조립 시점에 {@link
 * AccessJwtProperties#issuer()}에서 받는다. 같은 값을 두 곳에 적어 두면 한쪽만 바꾸는 사고가 난다.
 *
 * <p>key 값은 어떤 예외 message에도 넣지 않는다.
 */
@ConfigurationProperties("itda.security.jwt.refresh")
public record RefreshJwtProperties(
    String activeKid, String activeKey, @DefaultValue("") String retiredKeys) {

  private static final String PLACEHOLDER_PREFIX = "${";

  public RefreshJwtProperties {
    requireResolved(activeKid, "active-kid");
    requireResolved(activeKey, "active-key");
    AccessJwtProperties.requireValidKid(activeKid);
    if (retiredKeys == null) {
      throw new IllegalArgumentException("itda.security.jwt.refresh.retired-keys가 null입니다.");
    }
    if (retiredKeys.startsWith(PLACEHOLDER_PREFIX)) {
      throw new IllegalArgumentException(
          "itda.security.jwt.refresh.retired-keys가 해석되지 않은 placeholder입니다. 환경변수를 확인해 주세요.");
    }
  }

  private static void requireResolved(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("itda.security.jwt.refresh." + name + "이(가) 없습니다.");
    }
    if (value.startsWith(PLACEHOLDER_PREFIX)) {
      throw new IllegalArgumentException(
          "itda.security.jwt.refresh." + name + "이(가) 해석되지 않은 placeholder입니다. 환경변수를 확인해 주세요.");
    }
  }

  @Override
  public String toString() {
    return "RefreshJwtProperties[REDACTED]";
  }
}
