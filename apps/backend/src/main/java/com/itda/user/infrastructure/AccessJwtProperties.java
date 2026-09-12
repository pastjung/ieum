package com.itda.user.infrastructure;

import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access ring의 서명 key와 issuer 설정 (API 명세 3.6.1절).
 *
 * <p>기본값을 두지 않는다. 소스에 들어간 기본 key는 그대로 운영에 올라가고, 그 뒤에는 무엇으로 서명됐는지 알 수 없게 된다. Spring은 미해결
 * placeholder에 예외를 던지지 않고 리터럴을 그대로 넘기므로 {@code ${...}} 형태도 여기서 직접 거부한다.
 *
 * <p>key 값은 어떤 예외 message에도 넣지 않는다. 설정 오류 로그가 서명 key를 옮겨 적을 이유가 없다.
 */
@ConfigurationProperties("itda.security.jwt.access")
public record AccessJwtProperties(
    String issuer, String activeKid, String activeKey, @DefaultValue("") String retiredKeys) {

  /** API 명세 3.6.1절: 배포 환경별 논리 이름. 운영 도메인을 쓰지 않는다. */
  public static final Set<String> ALLOWED_ISSUERS =
      Set.of("itda-backend-local", "itda-backend-test", "itda-backend-prod");

  /** kid 형식. 쉼표와 콜론은 retired key 목록의 구분자이므로 허용하지 않는다. 길이는 UUID(36자)를 담고도 남게 잡되 무한정 늘어나지 않게 제한한다. */
  static final Pattern KID_PATTERN = Pattern.compile("^[A-Za-z0-9._~-]{1,64}$");

  private static final String PLACEHOLDER_PREFIX = "${";

  public AccessJwtProperties {
    requireResolved(issuer, "issuer");
    requireResolved(activeKid, "active-kid");
    requireResolved(activeKey, "active-key");
    if (!ALLOWED_ISSUERS.contains(issuer)) {
      throw new IllegalArgumentException(
          "itda.security.jwt.access.issuer는 itda-backend-local, itda-backend-test,"
              + " itda-backend-prod 중 하나여야 합니다.");
    }
    requireValidKid(activeKid);
    if (retiredKeys == null) {
      throw new IllegalArgumentException("itda.security.jwt.access.retired-keys가 null입니다.");
    }
    if (retiredKeys.startsWith(PLACEHOLDER_PREFIX)) {
      throw new IllegalArgumentException(
          "itda.security.jwt.access.retired-keys가 해석되지 않은 placeholder입니다. 환경변수를 확인해 주세요.");
    }
  }

  static void requireValidKid(String kid) {
    if (kid == null || !KID_PATTERN.matcher(kid).matches()) {
      throw new IllegalArgumentException(
          "Access key의 kid는 1~64자의 영문·숫자·'.', '_', '~', '-' 조합이어야 합니다.");
    }
  }

  /** 값 자체는 message에 넣지 않는다 — key가 여기로 흘러들 수 있다. */
  private static void requireResolved(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("itda.security.jwt.access." + name + "이(가) 없습니다.");
    }
    if (value.startsWith(PLACEHOLDER_PREFIX)) {
      throw new IllegalArgumentException(
          "itda.security.jwt.access." + name + "이(가) 해석되지 않은 placeholder입니다. 환경변수를 확인해 주세요.");
    }
  }

  /** 설정 값에 key가 들어 있으므로 기본 record 표현을 쓰지 않는다. */
  @Override
  public String toString() {
    return "AccessJwtProperties[REDACTED]";
  }
}
