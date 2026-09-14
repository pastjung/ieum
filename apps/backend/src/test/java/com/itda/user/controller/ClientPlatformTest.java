package com.itda.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 플랫폼 판정을 헤더 값 단위로 고정한다 (API 명세 3.3절).
 *
 * <p>HTTP 계층이 헤더 값의 앞뒤 공백을 이미 제거하므로 "우리가 trim하지 않는다"는 성질은 실제 요청으로 확인할 수 없다. 여기서 헤더 값을 직접 넣어 고정한다.
 *
 * <p>{@code Origin: null}도 같은 이유로 여기서 본다 — 허용 목록에 없는 Origin은 CORS가 먼저 거부해 이 판정까지 오지 않는다.
 */
class ClientPlatformTest {

  private static MockHttpServletRequest request(String... platformValues) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth-sessions");
    for (String value : platformValues) {
      request.addHeader(ClientPlatform.HEADER, value);
    }
    return request;
  }

  private static ErrorCode errorCodeOf(MockHttpServletRequest request) {
    return catchThrowableOfType(BusinessException.class, () -> ClientPlatform.from(request))
        .getErrorCode();
  }

  // ---------- 허용 ----------

  @Test
  void 정확히_일치하는_값만_받는다() {
    assertThat(ClientPlatform.from(request("WEB"))).isEqualTo(ClientPlatform.WEB);
    assertThat(ClientPlatform.from(request("MOBILE"))).isEqualTo(ClientPlatform.MOBILE);
  }

  @Test
  void Origin이_있어도_WEB은_허용한다() {
    MockHttpServletRequest request = request("WEB");
    request.addHeader("Origin", "http://localhost:5173");

    // 허용 Origin 검사는 CORS의 몫이다. 여기서 다시 판정하지 않는다.
    assertThat(ClientPlatform.from(request)).isEqualTo(ClientPlatform.WEB);
  }

  // ---------- 거부 ----------

  @Test
  void 헤더가_없으면_400이다() {
    assertThat(errorCodeOf(request())).isEqualTo(ErrorCode.INVALID_CLIENT_PLATFORM);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "web",
        "Web",
        "mobile",
        " WEB", // 앞 공백
        "WEB ", // 뒤 공백
        "\tMOBILE",
        "WEB,MOBILE",
        "DESKTOP"
      })
  void 값이_정확히_같지_않으면_400이다(String value) {
    // trim이나 대소문자 변환으로 받아 주면 클라이언트마다 다른 값을 보내게 되고 전달 방식이 흔들린다.
    assertThat(errorCodeOf(request(value))).isEqualTo(ErrorCode.INVALID_CLIENT_PLATFORM);
  }

  @Test
  void 헤더가_둘_이상이면_400이다() {
    assertThat(errorCodeOf(request("WEB", "MOBILE"))).isEqualTo(ErrorCode.INVALID_CLIENT_PLATFORM);
    assertThat(errorCodeOf(request("WEB", "WEB"))).isEqualTo(ErrorCode.INVALID_CLIENT_PLATFORM);
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://localhost:5173", "https://itda.example", "null"})
  void Origin이_있는_MOBILE_주장은_400이다(String origin) {
    MockHttpServletRequest request = request("MOBILE");
    request.addHeader("Origin", origin);

    // Origin은 브라우저가 붙이는 헤더다. 값이 무엇이든 그 요청은 네이티브 앱일 수 없다.
    // 문자열 "null"도 존재로 친다 — 값이 아니라 헤더의 유무가 판정 기준이다.
    assertThat(errorCodeOf(request)).isEqualTo(ErrorCode.INVALID_CLIENT_PLATFORM);
  }

  @Test
  void 거부_message에_헤더_값이_없다() {
    String rejected = "SUSPICIOUS-PLATFORM-VALUE";

    BusinessException thrown =
        catchThrowableOfType(BusinessException.class, () -> ClientPlatform.from(request(rejected)));

    assertThat(thrown.getErrorCode().getMessage()).doesNotContain(rejected);
    assertThat(thrown.getCause()).isNull();
  }
}
