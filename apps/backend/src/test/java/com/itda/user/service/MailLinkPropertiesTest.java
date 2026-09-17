package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.user.domain.AccountActionPurpose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 메일 링크 설정의 검증 규칙과 링크 렌더링을 고정한다 (API 명세 3.4절: 원문 토큰은 fragment로만, POST body로 완료). */
class MailLinkPropertiesTest {

  private static final String VERIFY = "https://app.example.com/verify-email";
  private static final String RESET = "https://app.example.com/reset-password";

  @Test
  void 용도별_링크는_base_URL_뒤에_fragment_token으로_토큰을_싣는다() {
    MailLinkProperties properties = new MailLinkProperties(VERIFY, RESET);

    assertThat(properties.linkFor(AccountActionPurpose.EMAIL_VERIFICATION, "abc.def.ghi"))
        .isEqualTo(VERIFY + "#token=abc.def.ghi");
    assertThat(properties.linkFor(AccountActionPurpose.PASSWORD_RESET, "abc.def.ghi"))
        .isEqualTo(RESET + "#token=abc.def.ghi");
  }

  @Test
  void 토큰의_URL_비안전_문자는_percent_encoding한다() {
    MailLinkProperties properties = new MailLinkProperties(VERIFY, RESET);

    String link = properties.linkFor(AccountActionPurpose.EMAIL_VERIFICATION, "a b#c&d=e/f?g");

    assertThat(link).isEqualTo(VERIFY + "#token=a+b%23c%26d%3De%2Ff%3Fg");
    // base64url 문자와 '.'은 그대로 남는다 — compact JWT는 바뀌지 않는다.
    assertThat(properties.linkFor(AccountActionPurpose.EMAIL_VERIFICATION, "ab-_.CD09"))
        .endsWith("#token=ab-_.CD09");
  }

  @Test
  void 토큰이_없으면_링크를_만들지_않는다() {
    MailLinkProperties properties = new MailLinkProperties(VERIFY, RESET);

    assertThatThrownBy(() -> properties.linkFor(AccountActionPurpose.EMAIL_VERIFICATION, " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties.linkFor(null, "abc"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost:5173/verify-email",
        "http://127.0.0.1/verify-email",
        "http://[::1]:3000/verify-email",
        "https://app.example.com",
        "https://app.example.com/path/with/segments"
      })
  void 외부_주소는_https만_허용하고_http는_루프백_주소에서만_허용한다(String allowed) {
    assertThat(new MailLinkProperties(allowed, RESET).emailVerificationUrl()).isEqualTo(allowed);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://app.example.com/verify-email",
        "https://user:secret@app.example.com/verify-email",
        "https://app.example.com/verify-email?next=home",
        "https://app.example.com/verify-email#token=already",
        "https://app.example.com/verify-email#",
        "/verify-email",
        "app.example.com/verify-email",
        "ftp://app.example.com/verify-email",
        "https:///verify-email",
        "https://app.example.com/verify email",
        " ",
        "https://app.example.com/verify-email\r\nX: y"
      })
  void 링크_base_URL이_규칙에_어긋나면_기동_단계에서_거부한다(String rejected) {
    assertThatThrownBy(() -> new MailLinkProperties(rejected, RESET))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.link.email-verification-url")
        .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("example.com"));
    assertThatThrownBy(() -> new MailLinkProperties(VERIFY, rejected))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.link.password-reset-url");
  }

  @Test
  void 해석되지_않은_환경변수_플레이스홀더는_거부한다() {
    assertThatThrownBy(() -> new MailLinkProperties("${MAIL_LINK_EMAIL_VERIFICATION_URL}", RESET))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.link.email-verification-url")
        .hasMessageContaining("플레이스홀더");
  }

  @Test
  void 링크_base_URL이_없으면_거부한다() {
    assertThatThrownBy(() -> new MailLinkProperties(null, RESET))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.link.email-verification-url");
    assertThatThrownBy(() -> new MailLinkProperties(VERIFY, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.link.password-reset-url");
  }
}
