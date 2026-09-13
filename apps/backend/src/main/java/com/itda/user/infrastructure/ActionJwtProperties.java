package com.itda.user.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * action ring의 서명 key 설정 (API 명세 3.6.1절).
 *
 * <p>이메일 인증과 비밀번호 재설정이 ring 하나를 공유한다. 계약이 "action 두 종류는 수명이 짧고 성격이 같아 한 ring으로 묶고 {@code purpose}로
 * 구분한다"고 정했기 때문이다. 종류 분리는 ring이 아니라 {@code typ}·{@code aud}·{@code purpose}와 전용 발급기·검증기가 담당한다.
 *
 * <p>{@code issuer}를 두지 않는다. 세 종류의 토큰이 같은 발급자를 쓰므로 환경변수를 하나만 유지하고, 값은 조립 시점에 {@link
 * AccessJwtProperties#issuer()}에서 받는다.
 *
 * <p>key 값은 어떤 예외 message에도 넣지 않는다.
 */
@ConfigurationProperties("itda.security.jwt.action")
public record ActionJwtProperties(
    String activeKid, String activeKey, @DefaultValue("") String retiredKeys) {

  private static final String PLACEHOLDER_PREFIX = "${";

  public ActionJwtProperties {
    requireResolved(activeKid, "active-kid");
    requireResolved(activeKey, "active-key");
    AccessJwtProperties.requireValidKid(activeKid);
    if (retiredKeys == null) {
      throw new IllegalArgumentException("itda.security.jwt.action.retired-keys가 null입니다.");
    }
    if (retiredKeys.startsWith(PLACEHOLDER_PREFIX)) {
      throw new IllegalArgumentException(
          "itda.security.jwt.action.retired-keys가 해석되지 않은 placeholder입니다. 환경변수를 확인해 주세요.");
    }
  }

  private static void requireResolved(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("itda.security.jwt.action." + name + "이(가) 없습니다.");
    }
    if (value.startsWith(PLACEHOLDER_PREFIX)) {
      throw new IllegalArgumentException(
          "itda.security.jwt.action." + name + "이(가) 해석되지 않은 placeholder입니다. 환경변수를 확인해 주세요.");
    }
  }

  @Override
  public String toString() {
    return "ActionJwtProperties[REDACTED]";
  }
}
