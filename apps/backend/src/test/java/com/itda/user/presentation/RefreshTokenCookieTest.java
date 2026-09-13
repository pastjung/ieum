package com.itda.user.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WEB Refresh 쿠키 헤더를 실행 시각과 무관하게 고정한다 (API 명세 3.3.1절 「WEB 쿠키」 표).
 *
 * <p><b>고정 만료 시각을 쓰는 것이 이 테스트의 존재 이유다.</b> 통합 테스트는 같은 요청에서 발급된 Refresh의 만료와 쿠키 {@code Expires}를
 * 잇지만, 두 시각이 같은 초 안에 들어오면 {@code Expires}를 "직렬화 시각 + Max-Age"로 계산하는 잘못된 구현도 우연히 같은 epoch second가 되어
 * 통과할 수 있다. 여기서는 현재와 한참 떨어진 시각을 넘기므로 그 구현이 반드시 어긋난다.
 *
 * <p>그래서 {@code Clock}·{@code Instant.now()}·오늘 날짜와 비교하지 않는다. 비교 대상은 넘긴 값 하나뿐이다.
 *
 * <p>단언 대상에서 쿠키 값을 제외한다 — 실패 메시지에 compact 문자열이 실리지 않게 한다.
 */
class RefreshTokenCookieTest {

  /** 실제 JWT가 아니다. 서명도 비밀값도 없는 형식 자리표시자다. */
  private static final String SAMPLE_COMPACT_VALUE = "header.payload.signature";

  /** 실행 시각과 겹칠 수 없는 고정 UTC 만료 시각. */
  private static final LocalDateTime FIXED_EXPIRES_AT = LocalDateTime.of(2035, 1, 15, 12, 34, 56);

  /** {@code Expires}는 RFC 1123 GMT 표기다. Locale에 따라 요일·월 약어가 바뀌지 않도록 ROOT로 고정한다. */
  private static final DateTimeFormatter RFC_1123 =
      DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);

  private String cookieName;
  private boolean valueMatchesToken;
  private List<String> attributes;

  @BeforeEach
  void setUp() {
    String header = RefreshTokenCookie.issue(SAMPLE_COMPACT_VALUE, FIXED_EXPIRES_AT);
    List<String> parts = Arrays.stream(header.split(";")).map(String::trim).toList();

    String nameAndValue = parts.get(0);
    this.cookieName = nameAndValue.substring(0, nameAndValue.indexOf('='));
    this.valueMatchesToken = nameAndValue.endsWith("=" + SAMPLE_COMPACT_VALUE);
    // 첫 조각(이름=값)을 뺀 나머지만 단언한다.
    this.attributes = parts.subList(1, parts.size());
  }

  private String attributeValue(String name) {
    String prefix = name + "=";
    return this.attributes.stream()
        .filter(attribute -> attribute.startsWith(prefix))
        .map(attribute -> attribute.substring(prefix.length()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(name + " 속성이 없습니다."));
  }

  // ---------- 만료 시각 ----------

  @Test
  void Expires는_넘긴_만료_시각_그대로다() {
    long parsed = Instant.from(RFC_1123.parse(attributeValue("Expires"))).getEpochSecond();

    // "현재 시각 + Max-Age"로 계산하면 2035년이 아니라 오늘 기준 값이 나와 반드시 어긋난다. 허용 오차를 두지 않는다.
    assertThat(parsed).isEqualTo(FIXED_EXPIRES_AT.toEpochSecond(ZoneOffset.UTC));
  }

  @Test
  void Expires는_UTC를_GMT로_표기한다() {
    // 서버 기본 시간대가 무엇이든 같은 문자열이어야 한다.
    assertThat(attributeValue("Expires")).isEqualTo("Mon, 15 Jan 2035 12:34:56 GMT");
  }

  // ---------- 이름과 값 ----------

  @Test
  void 이름은_Host_접두사를_쓴다() {
    // __Host- 접두사가 있어야 브라우저가 Secure·Path=/·Domain 생략을 강제한다.
    assertThat(this.cookieName).isEqualTo("__Host-refreshToken");
    assertThat(this.cookieName).isEqualTo(RefreshTokenCookie.NAME);
  }

  @Test
  void 값은_넘긴_토큰_그대로다() {
    // 값 자체를 단언 대상에 싣지 않는다. 일치 여부만 본다.
    assertThat(this.valueMatchesToken).as("쿠키 값이 넘긴 토큰과 같다").isTrue();
  }

  // ---------- 속성 ----------

  @Test
  void Max_Age는_계약이_고정한_14일이다() {
    assertThat(attributeValue("Max-Age")).isEqualTo("1209600");
  }

  @Test
  void Path는_루트다() {
    assertThat(attributeValue("Path")).isEqualTo("/");
  }

  @Test
  void Secure와_HttpOnly와_SameSite_Strict를_모두_붙인다() {
    assertThat(this.attributes).contains("Secure", "HttpOnly");
    assertThat(attributeValue("SameSite")).isEqualTo("Strict");
  }

  @Test
  void Domain을_붙이지_않는다() {
    // __Host- 접두사는 Domain을 금지한다. 붙이면 브라우저가 쿠키를 통째로 버린다.
    assertThat(this.attributes)
        .noneMatch(attribute -> attribute.toLowerCase(Locale.ROOT).startsWith("domain="));
  }
}
