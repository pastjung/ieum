package com.itda.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * 인증되지 않은 보호 요청의 진입 실패를 공통 오류 형식으로 넘긴다 (API 명세 3.5절 {@code 401 UNAUTHORIZED}).
 *
 * <p>body를 직접 쓰지 않고 {@code sendError}만 호출한다. 그러면 Issue #4가 만든 ERROR dispatch 경로가 그대로 이어져 Accept 협상,
 * 상태 보존, {@code X-Request-Id} 유지가 한곳에서 처리된다. 오류 조립 규칙이 두 벌로 갈라지지 않게 하는 것이 목적이다.
 *
 * <p>{@code sendError}에 message를 넘기지 않는다. 컨테이너가 그 문자열을 오류 페이지에 노출할 수 있다.
 *
 * <p>{@code WWW-Authenticate}는 scheme만 보낸다. {@code error}·{@code error_description}을 붙이면 만료·서명
 * 불일치·세대 불일치가 외부에서 구분되어 일반화 계약이 깨진다.
 */
public class AccessAuthenticationEntryPoint implements AuthenticationEntryPoint {

  static final String BEARER_CHALLENGE = "Bearer";

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
  }
}
