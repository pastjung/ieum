package com.itda.user.controller;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.error.FilterErrorResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 인증 세션 요청의 플랫폼을 <b>CORS보다 먼저</b> 판정한다 (API 명세 3.3절).
 *
 * <p>계약은 "{@code Origin} 헤더가 있으면 값이 허용 목록에 있는지와 <b>무관하게</b> MOBILE 주장을 {@code 400
 * INVALID_CLIENT_PLATFORM}으로 거부한다"고 정했다. 그런데 이 판정을 Controller에서만 하면 허용 목록에 없는 {@code Origin}(과
 * {@code Origin: null})은 Security의 CORS 처리가 먼저 {@code 403}으로 끝내 버려 판정까지 오지 못한다. 그래서 같은 규칙을 filter
 * 단계에서 한 번 더, 더 이른 자리에서 적용한다.
 *
 * <p><b>규칙을 복제하지 않는다.</b> 판정은 {@link ClientPlatform#from}뿐이고 여기서는 그 함수를 호출해 실패를 HTTP로 옮기기만 한다. 같은
 * 입력에 같은 결과를 주는 순수 판정이므로 Controller가 다시 호출해도 결과가 갈리지 않는다.
 *
 * <p>{@code OPTIONS}는 건드리지 않는다. CORS preflight에는 {@code X-Client-Platform}이 실리지 않으므로 여기서 거부하면 정상
 * WEB 요청의 preflight가 깨진다.
 *
 * <p>오류 body는 {@link FilterErrorResponder}가 공통 규칙 그대로 만든다. {@code X-Request-Id}는 더 앞선 추적 filter가 이미
 * 붙여 둔 값을 그대로 쓴다.
 */
public class ClientPlatformFilter extends OncePerRequestFilter {

  /**
   * Security filter chain({@code -100})보다 앞이다.
   *
   * <p>이 순서가 이 filter의 존재 이유다. 뒤에 있으면 CORS가 먼저 {@code 403}으로 끝내 계약이 정한 {@code 400}이 나가지 않는다.
   */
  public static final int ORDER =
      org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties
              .DEFAULT_FILTER_ORDER
          - 10;

  /** context-path 안의 경로. 등록 시 url pattern으로도 쓴다. */
  public static final String LOGIN_PATH = "/auth-sessions";

  /** Access Token 갱신 (API 명세 3.3.1절). */
  public static final String ACCESS_TOKEN_PATH = "/access-tokens";

  /** 로그아웃 — 현재 Refresh 계열 폐기 (API 명세 3.3.1절). */
  public static final String REVOCATION_PATH = "/auth-session-revocations";

  /**
   * 플랫폼 헤더를 필수로 받는 세 경로 (API 명세 3.3절).
   *
   * <p>목록을 여기에 두는 이유는 filter가 "어디에 걸리는지"를 스스로 말하게 하기 위해서다. 등록 설정에만 있으면 경로가 늘어날 때 filter를 읽는 사람이 적용
   * 범위를 알 수 없다.
   */
  public static final java.util.List<String> PATHS =
      java.util.List.of(LOGIN_PATH, ACCESS_TOKEN_PATH, REVOCATION_PATH);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!HttpMethod.POST.matches(request.getMethod())) {
      chain.doFilter(request, response);
      return;
    }
    try {
      ClientPlatform.from(request);
    } catch (BusinessException e) {
      reject(request, response, e.getErrorCode());
      return;
    }
    chain.doFilter(request, response);
  }

  /**
   * {@code sendError}를 쓰지 않는다.
   *
   * <p>그 경로는 {@code /error}로 다시 dispatch되면서 MVC CORS를 한 번 더 지나는데, 허용 목록에 없는 {@code Origin}이 붙은 요청은
   * 거기서 {@code 403}으로 바뀌어 버린다. 계약은 "허용 목록 여부와 무관하게 {@code 400}"이므로 여기서 응답을 완성한다.
   */
  private static void reject(
      HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
    FilterErrorResponder.send(request, response, code);
  }
}
