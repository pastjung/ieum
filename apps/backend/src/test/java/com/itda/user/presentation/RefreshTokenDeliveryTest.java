package com.itda.user.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import jakarta.servlet.http.Cookie;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 플랫폼별 Refresh Token 전달 위치 판정을 값 단위로 고정한다 (API 명세 3.3.1절 전달 위치 표).
 *
 * <p>실제 HTTP로는 만들기 번거로운 입력(같은 이름의 쿠키 여러 개, 값이 빈 쿠키)을 여기서 직접 넣는다.
 */
class RefreshTokenDeliveryTest {

  private static final String SAMPLE_COMPACT_VALUE = "refresh.compact.value";

  private final MockHttpServletRequest request =
      new MockHttpServletRequest("POST", "/api/v1/access-tokens");

  private void withCookies(String... values) {
    Cookie[] cookies = new Cookie[values.length];
    for (int i = 0; i < values.length; i++) {
      cookies[i] = new Cookie(RefreshTokenCookie.NAME, values[i]);
    }
    this.request.setCookies(cookies);
  }

  private RefreshTokenDelivery resolve(ClientPlatform platform, String bodyToken) {
    return RefreshTokenDelivery.resolve(this.request, platform, new RefreshTokenRequest(bodyToken));
  }

  private static ErrorCode errorCodeOf(ThrowingCall call) {
    return catchThrowableOfType(BusinessException.class, call::run).getErrorCode();
  }

  private interface ThrowingCall {
    void run();
  }

  // ---------- WEB ----------

  @Test
  void WEB은_쿠키에서_토큰을_읽는다() {
    withCookies(SAMPLE_COMPACT_VALUE);

    assertThat(resolve(ClientPlatform.WEB, null).requireToken()).isEqualTo(SAMPLE_COMPACT_VALUE);
  }

  @Test
  void WEB에_쿠키가_없으면_401이다() {
    assertThat(errorCodeOf(() -> resolve(ClientPlatform.WEB, null).requireToken()))
        .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
  }

  @Test
  void WEB의_쿠키가_하나이고_값이_비었으면_401이다() {
    withCookies("");

    assertThat(errorCodeOf(() -> resolve(ClientPlatform.WEB, null).requireToken()))
        .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
  }

  /**
   * 같은 이름의 쿠키가 둘 이상이면 값의 모양과 무관하게 거부한다.
   *
   * <p><b>빈 값을 먼저 걸러 내면 안 된다.</b> 걸러 내면 {@code 빈 값 + 유효한 값} 조합에서 하나만 남아, 단일 인증값을 고를 수 없는 요청이 정상
   * 요청으로 둔갑한다. 공격자가 빈 쿠키를 끼워 넣어 판정을 흔들 수 있는 자리다.
   */
  @ParameterizedTest
  @MethodSource("duplicateCookies")
  void WEB의_같은_이름_쿠키가_둘_이상이면_값과_무관하게_401이다(String first, String second) {
    withCookies(first, second);

    RefreshTokenDelivery delivery = resolve(ClientPlatform.WEB, null);

    assertThat(errorCodeOf(delivery::requireToken)).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
  }

  @ParameterizedTest
  @MethodSource("duplicateCookies")
  void WEB의_중복_쿠키에서는_어떤_값도_고르지_않는다(String first, String second) {
    withCookies(first, second);
    RefreshTokenDelivery delivery = resolve(ClientPlatform.WEB, null);

    String chosen;
    try {
      chosen = delivery.requireToken();
    } catch (BusinessException e) {
      chosen = null;
    }

    // 첫 번째·마지막·"비어 있지 않은 쪽" 중 무엇을 골라도 이 단언이 깨진다.
    assertThat(chosen).isNull();
  }

  private static Stream<Arguments> duplicateCookies() {
    return Stream.of(
        Arguments.of("", ""), // 둘 다 빈 값
        Arguments.of("", SAMPLE_COMPACT_VALUE), // 빈 값 + 유효한 값
        Arguments.of(SAMPLE_COMPACT_VALUE, ""), // 유효한 값 + 빈 값
        Arguments.of(SAMPLE_COMPACT_VALUE, SAMPLE_COMPACT_VALUE), // 같은 값이 두 번
        Arguments.of("first.compact.value", "second.compact.value")); // 서로 다른 값
  }

  @Test
  void WEB이_body로_토큰을_보내면_400이다() {
    withCookies(SAMPLE_COMPACT_VALUE);

    // 금지 위치 전달이다. 쿠키가 정상이어도 전달 경로가 확정되지 않는다.
    assertThat(errorCodeOf(() -> resolve(ClientPlatform.WEB, SAMPLE_COMPACT_VALUE)))
        .isEqualTo(ErrorCode.INVALID_CLIENT_PLATFORM);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {""})
  void WEB의_body_필드가_비어_있으면_금지_위치_사용이_아니다(String bodyToken) {
    withCookies(SAMPLE_COMPACT_VALUE);

    // body를 아예 안 보내는 클라이언트와 공용 요청 객체를 쓰는 클라이언트가 같은 결과를 받아야 한다.
    assertThat(resolve(ClientPlatform.WEB, bodyToken).requireToken())
        .isEqualTo(SAMPLE_COMPACT_VALUE);
  }

  @Test
  void WEB의_body가_없어도_금지_위치_사용이_아니다() {
    withCookies(SAMPLE_COMPACT_VALUE);

    assertThat(RefreshTokenDelivery.resolve(this.request, ClientPlatform.WEB, null).requireToken())
        .isEqualTo(SAMPLE_COMPACT_VALUE);
  }

  @Test
  void WEB의_공백만_있는_body_값은_제출로_본다() {
    withCookies(SAMPLE_COMPACT_VALUE);

    // 토큰으로는 무효지만 "보냈다"는 사실은 참이다.
    assertThat(errorCodeOf(() -> resolve(ClientPlatform.WEB, "   ")))
        .isEqualTo(ErrorCode.INVALID_CLIENT_PLATFORM);
  }

  // ---------- MOBILE ----------

  @Test
  void MOBILE은_body에서_토큰을_읽는다() {
    assertThat(resolve(ClientPlatform.MOBILE, SAMPLE_COMPACT_VALUE).requireToken())
        .isEqualTo(SAMPLE_COMPACT_VALUE);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {""})
  void MOBILE의_body가_비어_있으면_401이다(String bodyToken) {
    assertThat(errorCodeOf(() -> resolve(ClientPlatform.MOBILE, bodyToken).requireToken()))
        .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
  }

  @Test
  void MOBILE에_body가_없으면_401이다() {
    RefreshTokenDelivery delivery =
        RefreshTokenDelivery.resolve(this.request, ClientPlatform.MOBILE, null);

    assertThat(errorCodeOf(delivery::requireToken)).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
  }

  @Test
  void MOBILE이_Refresh_쿠키를_보내면_400이다() {
    withCookies(SAMPLE_COMPACT_VALUE);

    assertThat(errorCodeOf(() -> resolve(ClientPlatform.MOBILE, SAMPLE_COMPACT_VALUE)))
        .isEqualTo(ErrorCode.INVALID_CLIENT_PLATFORM);
  }

  @Test
  void MOBILE의_빈_값_쿠키는_금지_위치_사용이_아니다() {
    withCookies("");

    // 빈 쿠키는 토큰이 아니므로 MOBILE은 body 값을 그대로 쓴다.
    assertThat(resolve(ClientPlatform.MOBILE, SAMPLE_COMPACT_VALUE).requireToken())
        .isEqualTo(SAMPLE_COMPACT_VALUE);
  }

  @Test
  void MOBILE의_값_있는_쿠키는_body가_유효해도_400이다() {
    withCookies(SAMPLE_COMPACT_VALUE);

    assertThat(errorCodeOf(() -> resolve(ClientPlatform.MOBILE, SAMPLE_COMPACT_VALUE)))
        .isEqualTo(ErrorCode.INVALID_CLIENT_PLATFORM);
  }

  @Test
  void MOBILE의_값_있는_쿠키는_body가_없어도_400이다() {
    withCookies(SAMPLE_COMPACT_VALUE);

    assertThat(
            errorCodeOf(
                () -> RefreshTokenDelivery.resolve(this.request, ClientPlatform.MOBILE, null)))
        .isEqualTo(ErrorCode.INVALID_CLIENT_PLATFORM);
  }

  @Test
  void 양쪽에_모두_있으면_우선순위_없이_400이다() {
    withCookies(SAMPLE_COMPACT_VALUE);

    assertThat(errorCodeOf(() -> resolve(ClientPlatform.WEB, SAMPLE_COMPACT_VALUE)))
        .isEqualTo(ErrorCode.INVALID_CLIENT_PLATFORM);
    assertThat(errorCodeOf(() -> resolve(ClientPlatform.MOBILE, SAMPLE_COMPACT_VALUE)))
        .isEqualTo(ErrorCode.INVALID_CLIENT_PLATFORM);
  }

  // ---------- 판정 단계 분리 ----------

  @Test
  void 토큰_누락과_중복은_resolve가_아니라_requireToken에서_판정한다() {
    // 로그아웃은 "WEB이고 전달 경로 충돌이 없다"가 확정된 직후 삭제 쿠키를 붙여야 한다.
    // resolve가 401까지 던지면 그 쿠키를 붙이기 전에 응답이 끝난다.
    assertThatCode(() -> resolve(ClientPlatform.WEB, null)).doesNotThrowAnyException();

    withCookies("first.compact.value", "second.compact.value");
    assertThatCode(() -> resolve(ClientPlatform.WEB, null)).doesNotThrowAnyException();
  }

  @Test
  void 다른_이름의_쿠키는_보지_않는다() {
    this.request.setCookies(
        new Cookie("__Host-other", SAMPLE_COMPACT_VALUE),
        new Cookie(RefreshTokenCookie.NAME, SAMPLE_COMPACT_VALUE));

    assertThat(resolve(ClientPlatform.WEB, null).requireToken()).isEqualTo(SAMPLE_COMPACT_VALUE);
  }
}
