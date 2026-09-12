package com.itda.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

class BearerTokenExtractorTest {

  /** 추출기는 값의 의미를 보지 않는다. 실제 JWT 모양이 필요 없어 자리표시자를 쓴다. */
  private static final String SAMPLE = "test-bearer-value";

  private static Optional<String> extractFrom(String... authorizationValues) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    for (String value : authorizationValues) {
      request.addHeader(HttpHeaders.AUTHORIZATION, value);
    }
    return BearerTokenExtractor.extract(request);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Bearer", "bearer", "BEARER", "BeArEr"})
  void scheme의_대소문자를_구분하지_않는다(String scheme) {
    assertThat(extractFrom(scheme + " " + SAMPLE)).contains(SAMPLE);
  }

  @Test
  void scheme과_token_사이의_SP가_여러_개여도_받는다() {
    assertThat(extractFrom("Bearer   " + SAMPLE)).contains(SAMPLE);
  }

  @Test
  void Authorization이_없으면_빈_값이다() {
    assertThat(extractFrom()).isEmpty();
  }

  @Test
  void Authorization이_중복이면_받지_않는다() {
    assertThat(extractFrom("Bearer " + SAMPLE, "Bearer " + SAMPLE)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Bearer",
        "Bearer ",
        "Bearer  ",
        "Bearer abc def",
        "Bearer abc,def",
        "Bearer abc,Bearer def",
        "Bearerabc",
        "Bearer abc "
      })
  void 형식이_어긋난_Bearer_값은_받지_않는다(String headerValue) {
    assertThat(extractFrom(headerValue)).isEmpty();
  }

  @Test
  void 탭은_구분자로_받지_않는다() {
    assertThat(extractFrom("Bearer" + (char) 9 + SAMPLE)).isEmpty();
  }

  @Test
  void 제어_문자가_섞이면_받지_않는다() {
    assertThat(extractFrom("Bearer ab" + (char) 0 + "c")).isEmpty();
    assertThat(extractFrom("Bearer ab" + (char) 10 + "c")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Basic dXNlcjpwYXNz", "Digest abc", "Token abc", "DPoP abc"})
  void Bearer가_아닌_scheme은_익명_요청으로_둔다(String headerValue) {
    assertThat(extractFrom(headerValue)).isEmpty();
  }

  @Test
  void query_cookie_body의_값은_읽지_않는다() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("access_token", SAMPLE);
    request.setCookies(new Cookie("access_token", SAMPLE));
    request.setContent(("access_token=" + SAMPLE).getBytes(StandardCharsets.UTF_8));

    assertThat(BearerTokenExtractor.extract(request)).isEmpty();
  }

  @Test
  void Base64URL과_JWS_구분자를_허용한다() {
    assertThat(extractFrom("Bearer a-b_c.d.e")).contains("a-b_c.d.e");
    assertThat(extractFrom("Bearer YWJj==")).contains("YWJj==");
  }

  @Test
  void principal은_토큰과_식별자를_노출하지_않는다() {
    assertThat(new AuthenticatedUser(42L, com.itda.user.domain.UserRole.ADMIN).toString())
        .isEqualTo("AuthenticatedUser[REDACTED]")
        .doesNotContain("42")
        .doesNotContain("ADMIN");
  }
}
