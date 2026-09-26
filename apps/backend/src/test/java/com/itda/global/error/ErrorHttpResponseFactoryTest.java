package com.itda.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Accept 판정 경계 (docs/conventions/code/api.md "오류 상태 보존과 body 생략").
 *
 * <p>값이 빈 Accept는 HTTP 통합 테스트로 재현할 수 없다 — JDK HttpClient가 빈 값·공백 헤더를 요청에서 제거한다. raw socket을 쓰지 않고
 * 여기서 mock 요청으로 검증한다.
 */
class ErrorHttpResponseFactoryTest {

  @Test
  void Accept가_없으면_JSON을_허용으로_본다() {
    assertThat(ErrorHttpResponseFactory.acceptsJson(new MockHttpServletRequest())).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "   "})
  void 값이_빈_Accept는_별표_별표와_같게_허용으로_본다(String accept) {
    assertThat(ErrorHttpResponseFactory.acceptsJson(request(accept))).isTrue();
  }

  @Test
  void 빈_값과_실제_값이_섞이면_실제_값으로_판정한다() {
    assertThat(ErrorHttpResponseFactory.acceptsJson(request("", MediaType.APPLICATION_JSON_VALUE)))
        .isTrue();
    assertThat(ErrorHttpResponseFactory.acceptsJson(request("", MediaType.APPLICATION_XML_VALUE)))
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "*/*",
        "application/*",
        "application/json",
        "application/json;charset=UTF-8",
        "application/json;q=0.001",
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
      })
  void JSON을_허용하는_Accept를_구분한다(String accept) {
    assertThat(ErrorHttpResponseFactory.acceptsJson(request(accept))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "text/html",
        "application/xml",
        "application/json;q=0, */*",
        "*/*;q=0",
        "not a media type"
      })
  void JSON을_허용하지_않는_Accept를_구분한다(String accept) {
    assertThat(ErrorHttpResponseFactory.acceptsJson(request(accept))).isFalse();
  }

  private static MockHttpServletRequest request(String... acceptValues) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    for (String value : acceptValues) {
      request.addHeader(HttpHeaders.ACCEPT, value);
    }
    return request;
  }
}
