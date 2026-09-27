package com.itda.user.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 유출 비밀번호 조회(Pwned Passwords range API) 연결 설정.
 *
 * <p>검사를 끄는 스위치를 두지 않는다. API 명세 3.6.2절이 이 검사를 fail-closed로 못박았으므로, 끌 수 있는 설정은 운영에서 조용히 fail-open이
 * 되는 경로가 된다. 조회 대상은 base URL로만 바꿀 수 있고 평문 HTTP는 허용하지 않는다.
 *
 * <p>타임아웃이 짧은 이유는 이 조회가 가입·비밀번호 재설정 요청 안에서 동기로 일어나기 때문이다. 오래 매달리는 것보다 fail-closed로 끊는 편이 낫다.
 */
@ConfigurationProperties("itda.security.password.breach")
public record BreachCheckProperties(
    @DefaultValue("https://api.pwnedpasswords.com/range/") String baseUrl,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("3s") Duration readTimeout) {

  private static final String INVALID_BASE_URL_MESSAGE =
      "itda.security.password.breach.base-url은 사용자 정보·질의·프래그먼트가 없고 '/'로 끝나는 절대 https URL이어야 합니다.";

  public BreachCheckProperties {
    requireSafeBaseUrl(baseUrl);
    requirePositive(connectTimeout, "connect-timeout");
    requirePositive(readTimeout, "read-timeout");
  }

  /**
   * 문자열 접두사 검사로는 부족해 URI로 파싱해 확인한다. prefix를 그대로 이어 붙이는 base URL이므로, 경로가 {@code /}로 끝나지 않으면 마지막 경로
   * 조각이 잘려 다른 주소로 나가고, 질의·프래그먼트가 붙어 있으면 이어 붙인 prefix가 값의 일부로 흘러 들어간다. 자격 증명이 URL에 실리는 것도 막는다.
   *
   * <p>message에 입력값을 넣지 않는다 — 설정 오류 로그가 외부 주소를 그대로 옮겨 적을 이유가 없다.
   */
  private static void requireSafeBaseUrl(String baseUrl) {
    if (baseUrl == null) {
      throw new IllegalArgumentException(INVALID_BASE_URL_MESSAGE);
    }
    URI uri;
    try {
      uri = new URI(baseUrl);
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException(INVALID_BASE_URL_MESSAGE);
    }
    boolean valid =
        uri.isAbsolute()
            && "https".equalsIgnoreCase(uri.getScheme())
            && uri.getHost() != null
            && !uri.getHost().isEmpty()
            && uri.getUserInfo() == null
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null
            && uri.getRawPath() != null
            && uri.getRawPath().endsWith("/");
    if (!valid) {
      throw new IllegalArgumentException(INVALID_BASE_URL_MESSAGE);
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(
          "itda.security.password.breach." + name + "은(는) 0보다 커야 합니다.");
    }
  }
}
