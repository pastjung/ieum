package com.itda.global.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;

/**
 * Authorization 헤더에서 Access Token 원문을 꺼낸다 (RFC 6750 §2.1).
 *
 * <p>관대하게 파싱하지 않는다. 중복 헤더나 쉼표로 이어 붙인 값을 받아 주면 앞단 프록시와 서버의 해석이 갈리는 지점이 생긴다. 그래서 Authorization은 정확히
 * 하나여야 하고, {@code Bearer}와 토큰 사이는 SP만 허용하며, 토큰 안에 공백·쉼표·제어 문자가 있으면 받지 않는다.
 *
 * <p>query·Cookie·body는 보지 않는다. URL과 로그·Referer에 남는 경로로 자격 증명이 흐르지 않게 한다.
 *
 * <p>"헤더 없음"과 "형식 오류"를 같은 빈 값으로 돌려주는 이유: 이 추출기는 보호 경로에서만 동작하고, 그 경로에서 두 경우 모두 인증되지 않은 요청으로서 동일한
 * {@code 401 UNAUTHORIZED}가 된다. 둘을 타입으로 나누면 응답이나 로그에서 구분될 통로만 늘어난다.
 */
public final class BearerTokenExtractor {

  private static final String BEARER_SCHEME = "Bearer";

  /** Base64URL과 JWS 구분자만 허용한다. 공백·쉼표·제어 문자는 여기서 걸린다. */
  private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9._~+/-]+=*$");

  private BearerTokenExtractor() {}

  /**
   * @return Bearer 토큰. 헤더가 없거나 다른 scheme이거나 형식이 어긋나면 빈 값
   */
  public static Optional<String> extract(HttpServletRequest request) {
    List<String> values = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
    if (values.size() != 1) {
      return Optional.empty();
    }
    String value = values.get(0);
    if (!value.regionMatches(true, 0, BEARER_SCHEME, 0, BEARER_SCHEME.length())) {
      return Optional.empty();
    }
    int index = BEARER_SCHEME.length();
    while (index < value.length() && value.charAt(index) == ' ') {
      index++;
    }
    if (index == BEARER_SCHEME.length()) {
      // scheme 바로 뒤에 SP가 없다. "Bearertoken" 같은 값은 Bearer 자격 증명이 아니다.
      return Optional.empty();
    }
    String token = value.substring(index);
    if (token.isEmpty() || !TOKEN_PATTERN.matcher(token).matches()) {
      return Optional.empty();
    }
    return Optional.of(token);
  }
}
