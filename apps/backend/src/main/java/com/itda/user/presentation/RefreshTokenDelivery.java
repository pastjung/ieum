package com.itda.user.presentation;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;

/**
 * 플랫폼별 Refresh Token 전달 위치 판정 (API 명세 3.3.1절 전달 위치 표).
 *
 * <p>WEB은 {@code __Host-refreshToken} 쿠키만, MOBILE은 body {@code refreshToken}만 쓴다. 금지 위치에 값이 하나라도
 * 있으면 {@code 400 INVALID_CLIENT_PLATFORM}이고, 허용 위치에 없으면 {@code 401 INVALID_REFRESH_TOKEN}이다.
 *
 * <p><b>판정을 두 단계로 나눈다.</b> {@link #resolve}는 금지 위치만 보고 {@code 400}을 결정하며, 허용 위치의 값 추출은 {@link
 * #requireToken}이 맡는다. 로그아웃은 "WEB이고 전달 경로 충돌이 없다"가 확정된 직후 삭제 쿠키를 응답에 붙여야 하는데(3.3.1·3.6.2절), 두 판정을 한
 * 번에 하면 토큰 누락 {@code 401}이 쿠키를 붙이기 전에 터진다.
 *
 * <p>값을 비교하거나 파싱하지 않는다. 여기서 하는 일은 "어느 위치에서 몇 개가 왔는가"뿐이고, 서명·만료·저장소 상태 판정은 application의 몫이다.
 */
final class RefreshTokenDelivery {

  /**
   * 허용 위치에 <b>실제로 도착한 값들</b>. 빈 값도 빼지 않고 그대로 담는다.
   *
   * <p>빈 값을 먼저 걸러 내면 "같은 이름의 쿠키가 몇 개 왔는가"를 잃는다. 그러면 {@code __Host-refreshToken=}과 {@code
   * __Host-refreshToken=<값>}이 함께 온 요청에서 값 있는 쪽 하나만 남아, 단일 인증값을 고를 수 없는 요청이 정상 요청으로 둔갑한다.
   */
  private final List<String> submitted;

  private RefreshTokenDelivery(List<String> submitted) {
    this.submitted = submitted;
  }

  /**
   * 금지 위치 사용만 판정한다.
   *
   * @param body 없을 수 있다. WEB 요청은 body 자체가 계약상 필요 없다
   * @throws BusinessException 금지 위치에 값이 있으면 {@code INVALID_CLIENT_PLATFORM}
   */
  static RefreshTokenDelivery resolve(
      HttpServletRequest request, ClientPlatform platform, RefreshTokenRequest body) {
    List<String> cookies = refreshCookieValues(request);
    String bodyValue = body == null ? null : body.refreshToken();
    if (platform == ClientPlatform.WEB) {
      rejectForbidden(hasValue(bodyValue));
      return new RefreshTokenDelivery(cookies);
    }
    // 빈 쿠키는 토큰이 아니다. 값이 실제로 실린 쿠키만 금지 위치 전달로 본다.
    rejectForbidden(cookies.stream().anyMatch(RefreshTokenDelivery::hasValue));
    return new RefreshTokenDelivery(bodyValue == null ? List.of() : List.of(bodyValue));
  }

  /**
   * 허용 위치의 값을 하나로 확정한다.
   *
   * <p>도착한 값이 둘 이상이면 <b>어느 것도 고르지 않는다.</b> 값이 비었는지, 서로 같은지 다른지도 보지 않는다 — 첫 번째나 마지막이나 "비어 있지 않은 쪽"을
   * 임의로 쓰면 어떤 값이 인증에 쓰일지 클라이언트·프록시 구현에 따라 달라지고, 주입된 쿠키가 선택될 여지가 생긴다.
   *
   * <p>하나만 왔더라도 값이 비어 있으면 토큰을 보내지 않은 것이다.
   *
   * <p>두 경우 모두 플랫폼과 전달 위치는 이미 확정됐고 단일 인증값만 고르지 못하는 상태이므로 플랫폼 오류가 아니라 토큰 오류다.
   *
   * @throws BusinessException 없거나 비었거나 둘 이상이면 {@code INVALID_REFRESH_TOKEN}
   */
  String requireToken() {
    if (this.submitted.size() != 1) {
      throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
    }
    String value = this.submitted.get(0);
    if (!hasValue(value)) {
      throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
    }
    return value;
  }

  private static void rejectForbidden(boolean present) {
    if (present) {
      throw new BusinessException(ErrorCode.INVALID_CLIENT_PLATFORM);
    }
  }

  /**
   * 값이 없는 것과 빈 값은 같게 본다.
   *
   * <p>필드 부재·{@code null}·{@code ""}는 "토큰을 보내지 않았다"로 통일한다. 그래야 body를 아예 안 보내는 WEB 클라이언트와 공용 요청 객체를
   * 쓰는 WEB 클라이언트가 같은 결과를 받는다. 공백만 있는 값은 비어 있지 않으므로 제출된 값으로 본다 — 토큰으로는 무효지만 "보냈다"는 사실은 참이다.
   *
   * <p>이 판정은 <b>값 하나</b>에만 쓴다. 여러 개가 왔는지는 개수로 따로 본다.
   */
  private static boolean hasValue(String value) {
    return value != null && !value.isEmpty();
  }

  /**
   * 같은 이름으로 도착한 쿠키 값을 <b>개수를 보존한 채</b> 모은다.
   *
   * <p>여기서 빈 값을 걸러 내면 개수 정보가 사라진다. {@code null} 값 쿠키도 빈 문자열로 바꿔 자리를 남긴다 — 자리를 지우면 같은 문제가 생긴다.
   */
  private static List<String> refreshCookieValues(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return List.of();
    }
    return Arrays.stream(cookies)
        .filter(cookie -> RefreshTokenCookie.NAME.equals(cookie.getName()))
        .map(cookie -> cookie.getValue() == null ? "" : cookie.getValue())
        .toList();
  }
}
