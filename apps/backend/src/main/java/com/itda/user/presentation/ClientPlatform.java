package com.itda.user.presentation;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;

/**
 * Refresh Token 전달 방식을 고르는 클라이언트 플랫폼 (API 명세 3.3절).
 *
 * <p><b>인증·권한의 근거가 아니다.</b> 요청이 주장하는 값일 뿐이므로 역할·신원 판정에 쓰지 않는다. 이 값이 정하는 것은 "Refresh Token을 쿠키로 보낼지
 * body에 실을지"뿐이다.
 */
public enum ClientPlatform {
  WEB,
  MOBILE;

  static final String HEADER = "X-Client-Platform";

  private static final String ORIGIN_HEADER = "Origin";

  /**
   * 요청에서 플랫폼을 확정한다.
   *
   * <p>헤더는 <b>정확히 하나</b>여야 하고 값은 {@code WEB} 또는 {@code MOBILE}과 정확히 같아야 한다. trim이나 대소문자 변환으로 느슨하게
   * 받아 주지 않는다 — 관대하게 해석할수록 클라이언트마다 다른 값을 보내게 되고, 전달 방식이 요청마다 달라지면 쿠키와 body가 뒤섞인다.
   *
   * <p>{@code Origin}이 있는 요청의 {@code MOBILE} 주장은 거부한다. {@code Origin}은 브라우저가 붙이는 헤더라 그 요청은 네이티브 앱일
   * 수 없고, 브라우저에게 쿠키 대신 body로 Refresh Token을 내주면 스크립트가 읽을 수 있는 자리에 놓이게 된다. 값이 허용 목록에 있는지는 보지 않으며
   * {@code Origin: null}도 존재로 친다.
   *
   * @throws BusinessException 헤더가 없거나 하나가 아니거나 값이 다르거나, {@code Origin}이 있는 MOBILE 요청일 때 {@code
   *     INVALID_CLIENT_PLATFORM}
   */
  public static ClientPlatform from(HttpServletRequest request) {
    List<String> values = Collections.list(request.getHeaders(HEADER));
    if (values.size() != 1) {
      throw new BusinessException(ErrorCode.INVALID_CLIENT_PLATFORM);
    }
    ClientPlatform platform = parse(values.get(0));
    if (platform == MOBILE && request.getHeader(ORIGIN_HEADER) != null) {
      throw new BusinessException(ErrorCode.INVALID_CLIENT_PLATFORM);
    }
    return platform;
  }

  private static ClientPlatform parse(String value) {
    for (ClientPlatform platform : values()) {
      if (platform.name().equals(value)) {
        return platform;
      }
    }
    // 값을 message에 넣지 않는다. 요청이 보낸 문자열이 로그로 흘러갈 이유가 없다.
    throw new BusinessException(ErrorCode.INVALID_CLIENT_PLATFORM);
  }
}
