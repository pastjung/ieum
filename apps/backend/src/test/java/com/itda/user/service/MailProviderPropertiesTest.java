package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** SMTP 제공자 설정의 검증 규칙과 비밀값 비노출을 고정한다. */
class MailProviderPropertiesTest {

  private static final String PASSWORD = "test-smtp-password";
  private static final String SENDER = "sender@example.com";
  private static final Duration TEN_SECONDS = Duration.ofSeconds(10);
  private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30);

  /** 실제 YAML의 공통 설정만 읽는다. 프로필 import나 개인 .env·OS 환경변수는 읽지 않는다. */
  private static MailProviderProperties bindApplicationSettings(String fromName)
      throws IOException {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("MAIL_SMTP_HOST", "127.0.0.1")
            .withProperty("MAIL_SMTP_PORT", "2525")
            .withProperty("MAIL_SMTP_SECURITY", "PLAINTEXT")
            .withProperty("MAIL_FROM_ADDRESS", SENDER);
    if (fromName != null) {
      environment.setProperty("MAIL_FROM_NAME", fromName);
    }
    environment
        .getPropertySources()
        .addLast(
            new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0));
    return Binder.get(environment).bind("itda.mail.provider", MailProviderProperties.class).get();
  }

  @Test
  void 표시_이름을_설정하지_않으면_서비스_기본값을_바인딩한다() throws IOException {
    MailProviderProperties properties = bindApplicationSettings(null);

    assertThat(properties.fromName()).isEqualTo("이음(I:EUM)");
    assertThat(properties.fromAddress()).isEqualTo(SENDER);
    assertThat(properties.replyTo()).isNull();
  }

  @Test
  void 표시_이름은_환경설정으로_교체할_수_있다() throws IOException {
    assertThat(bindApplicationSettings("테스트 서비스").fromName()).isEqualTo("테스트 서비스");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void 명시적으로_빈_표시_이름을_설정하면_기존_생략_정책을_유지한다(String fromName) throws IOException {
    assertThat(bindApplicationSettings(fromName).fromName()).isNull();
  }

  private static MailProviderProperties build(
      String host,
      int port,
      MailProviderProperties.Security security,
      String username,
      String password,
      String fromAddress,
      String fromName,
      String replyTo,
      Duration connectTimeout,
      Duration readTimeout,
      Duration writeTimeout) {
    return new MailProviderProperties(
        host,
        port,
        security,
        username,
        password,
        fromAddress,
        fromName,
        replyTo,
        connectTimeout,
        readTimeout,
        writeTimeout);
  }

  private static MailProviderProperties valid() {
    return build(
        "smtp.example.com",
        465,
        MailProviderProperties.Security.SSL,
        SENDER,
        PASSWORD,
        SENDER,
        "이음",
        null,
        TEN_SECONDS,
        THIRTY_SECONDS,
        THIRTY_SECONDS);
  }

  @Test
  void 유효한_설정은_그대로_보관하고_자격증명_보유_여부를_알려준다() {
    MailProviderProperties properties = valid();

    assertThat(properties.host()).isEqualTo("smtp.example.com");
    assertThat(properties.port()).isEqualTo(465);
    assertThat(properties.security()).isEqualTo(MailProviderProperties.Security.SSL);
    assertThat(properties.hasCredentials()).isTrue();
    assertThat(properties.replyTo()).isNull();
  }

  @Test
  void toString은_비밀번호와_계정을_드러내지_않는다() {
    String text = valid().toString();

    assertThat(text).doesNotContain(PASSWORD).doesNotContain(SENDER);
    assertThat(text).contains("smtp.example.com").contains("SSL");
  }

  @Test
  void 검증_실패_message에는_설정값이_들어가지_않는다() {
    assertThatThrownBy(
            () ->
                build(
                    "smtp.example.com",
                    0,
                    MailProviderProperties.Security.SSL,
                    SENDER,
                    PASSWORD,
                    SENDER,
                    null,
                    null,
                    TEN_SECONDS,
                    THIRTY_SECONDS,
                    THIRTY_SECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.port")
        .satisfies(
            ex -> assertThat(ex.getMessage()).doesNotContain(PASSWORD).doesNotContain(SENDER));
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", "smtp.example.com:465", "smtp example.com", "smtp.example.com\n"})
  void host가_비어_있거나_공백이나_구분자를_포함하면_거부한다(String host) {
    assertThatThrownBy(
            () ->
                build(
                    host,
                    465,
                    MailProviderProperties.Security.SSL,
                    SENDER,
                    PASSWORD,
                    SENDER,
                    null,
                    null,
                    TEN_SECONDS,
                    THIRTY_SECONDS,
                    THIRTY_SECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.host");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 65536})
  void port가_1에서_65535_범위를_벗어나면_거부한다(int port) {
    assertThatThrownBy(
            () ->
                build(
                    "smtp.example.com",
                    port,
                    MailProviderProperties.Security.STARTTLS,
                    SENDER,
                    PASSWORD,
                    SENDER,
                    null,
                    null,
                    TEN_SECONDS,
                    THIRTY_SECONDS,
                    THIRTY_SECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.port");
  }

  @Test
  void 보안_방식이_없으면_거부한다() {
    assertThatThrownBy(
            () ->
                build(
                    "smtp.example.com",
                    587,
                    null,
                    SENDER,
                    PASSWORD,
                    SENDER,
                    null,
                    null,
                    TEN_SECONDS,
                    THIRTY_SECONDS,
                    THIRTY_SECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.security");
  }

  @Test
  void SSL과_STARTTLS는_계정과_비밀번호가_모두_있어야_한다() {
    for (MailProviderProperties.Security security :
        new MailProviderProperties.Security[] {
          MailProviderProperties.Security.SSL, MailProviderProperties.Security.STARTTLS
        }) {
      assertThatThrownBy(
              () ->
                  build(
                      "smtp.example.com",
                      587,
                      security,
                      null,
                      PASSWORD,
                      SENDER,
                      null,
                      null,
                      TEN_SECONDS,
                      THIRTY_SECONDS,
                      THIRTY_SECONDS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("itda.mail.provider.username");
      assertThatThrownBy(
              () ->
                  build(
                      "smtp.example.com",
                      587,
                      security,
                      SENDER,
                      " ",
                      SENDER,
                      null,
                      null,
                      TEN_SECONDS,
                      THIRTY_SECONDS,
                      THIRTY_SECONDS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("itda.mail.provider.password")
          .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(PASSWORD));
    }
  }

  @Test
  void PLAINTEXT는_자격증명_없이_구성할_수_있고_하나만_주면_거부한다() {
    MailProviderProperties withoutCredentials =
        build(
            "127.0.0.1",
            2525,
            MailProviderProperties.Security.PLAINTEXT,
            null,
            null,
            SENDER,
            null,
            null,
            TEN_SECONDS,
            THIRTY_SECONDS,
            THIRTY_SECONDS);
    assertThat(withoutCredentials.hasCredentials()).isFalse();

    assertThatThrownBy(
            () ->
                build(
                    "127.0.0.1",
                    2525,
                    MailProviderProperties.Security.PLAINTEXT,
                    "user",
                    null,
                    SENDER,
                    null,
                    null,
                    TEN_SECONDS,
                    THIRTY_SECONDS,
                    THIRTY_SECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.password");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        " ",
        "sender",
        "sender@",
        "@example.com",
        "이음 <sender@example.com>",
        "sender@example.com, other@example.com",
        "sender@example.com\r\nBcc: x@example.com",
        "sen der@example.com"
      })
  void 발신_주소는_표시_이름_없는_단일_주소여야_한다(String fromAddress) {
    assertThatThrownBy(
            () ->
                build(
                    "smtp.example.com",
                    465,
                    MailProviderProperties.Security.SSL,
                    SENDER,
                    PASSWORD,
                    fromAddress,
                    null,
                    null,
                    TEN_SECONDS,
                    THIRTY_SECONDS,
                    THIRTY_SECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.from-address")
        .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("example.com"));
  }

  @Test
  void 발신_주소가_없으면_기본값을_만들지_않고_거부한다() {
    assertThatThrownBy(
            () ->
                build(
                    "smtp.example.com",
                    465,
                    MailProviderProperties.Security.SSL,
                    SENDER,
                    PASSWORD,
                    null,
                    null,
                    null,
                    TEN_SECONDS,
                    THIRTY_SECONDS,
                    THIRTY_SECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.from-address");
  }

  @Test
  void 빈_문자열_선택값은_없는_것으로_본다() {
    MailProviderProperties properties =
        build(
            "127.0.0.1",
            2525,
            MailProviderProperties.Security.PLAINTEXT,
            "",
            " ",
            SENDER,
            " ",
            "",
            TEN_SECONDS,
            THIRTY_SECONDS,
            THIRTY_SECONDS);

    // application.yml의 ${ENV:}는 null이 아니라 ""를 바인딩한다. 그 값이 표시 이름·회신 주소·자격증명으로 쓰이면 안 된다.
    assertThat(properties.hasCredentials()).isFalse();
    assertThat(properties.username()).isNull();
    assertThat(properties.password()).isNull();
    assertThat(properties.fromName()).isNull();
    assertThat(properties.replyTo()).isNull();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void null과_blank_선택값은_모두_없는_것으로_정규화한다(String absent) {
    MailProviderProperties properties =
        build(
            "127.0.0.1",
            2525,
            MailProviderProperties.Security.PLAINTEXT,
            absent,
            absent,
            SENDER,
            absent,
            absent,
            TEN_SECONDS,
            THIRTY_SECONDS,
            THIRTY_SECONDS);

    assertThat(properties.username()).isNull();
    assertThat(properties.password()).isNull();
    assertThat(properties.fromName()).isNull();
    assertThat(properties.replyTo()).isNull();
    assertThat(properties.hasCredentials()).isFalse();
  }

  @Test
  void 비어_있지_않은_선택값의_앞뒤_공백을_자르지_않는다() {
    String paddedCredential = " " + PASSWORD + " ";
    MailProviderProperties properties =
        build(
            "127.0.0.1",
            465,
            MailProviderProperties.Security.SSL,
            " " + SENDER + " ",
            paddedCredential,
            SENDER,
            " 이음(I:EUM) ",
            "support@example.com",
            TEN_SECONDS,
            THIRTY_SECONDS,
            THIRTY_SECONDS);

    assertThat(properties.username()).isEqualTo(" " + SENDER + " ");
    assertThat(properties.password().equals(paddedCredential)).as("인증 값 보존").isTrue();
    assertThat(properties.fromName()).isEqualTo(" 이음(I:EUM) ");
    assertThat(properties.replyTo()).isEqualTo("support@example.com");
    assertThat(properties.hasCredentials()).isTrue();
  }

  @Test
  void YAML_바인딩도_비어_있지_않은_표시_이름을_trim하지_않는다() throws IOException {
    assertThat(bindApplicationSettings(" 이음(I:EUM) ").fromName()).isEqualTo(" 이음(I:EUM) ");
  }

  @Test
  void 회신_주소의_공백은_자동으로_고치지_않고_기존_주소_검증으로_거부한다() {
    assertThatThrownBy(
            () ->
                build(
                    "127.0.0.1",
                    465,
                    MailProviderProperties.Security.SSL,
                    SENDER,
                    PASSWORD,
                    SENDER,
                    null,
                    " support@example.com ",
                    TEN_SECONDS,
                    THIRTY_SECONDS,
                    THIRTY_SECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.reply-to");
  }

  @ParameterizedTest
  @ValueSource(strings = {"${MAIL_SMTP_HOST}", "smtp.${MAIL_DOMAIN}"})
  void 해석되지_않은_환경변수_플레이스홀더는_거부한다(String host) {
    assertThatThrownBy(
            () ->
                build(
                    host,
                    465,
                    MailProviderProperties.Security.SSL,
                    SENDER,
                    PASSWORD,
                    SENDER,
                    null,
                    null,
                    TEN_SECONDS,
                    THIRTY_SECONDS,
                    THIRTY_SECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.host")
        .hasMessageContaining("플레이스홀더");
    assertThatThrownBy(
            () ->
                build(
                    "smtp.example.com",
                    465,
                    MailProviderProperties.Security.SSL,
                    SENDER,
                    "${MAIL_SMTP_PASSWORD}",
                    SENDER,
                    null,
                    null,
                    TEN_SECONDS,
                    THIRTY_SECONDS,
                    THIRTY_SECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.password")
        .hasMessageContaining("플레이스홀더");
  }

  @ParameterizedTest
  @ValueSource(strings = {"이음\r\nBcc: x@example.com", "이음\n"})
  void 표시_이름에_줄바꿈이_오면_거부한다(String fromName) {
    assertThatThrownBy(
            () ->
                build(
                    "smtp.example.com",
                    465,
                    MailProviderProperties.Security.SSL,
                    SENDER,
                    PASSWORD,
                    SENDER,
                    fromName,
                    null,
                    TEN_SECONDS,
                    THIRTY_SECONDS,
                    THIRTY_SECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.from-name");
  }

  @Test
  void 회신_주소는_선택이며_주면_발신_주소와_같은_규칙으로_검증한다() {
    assertThatCode(
            () ->
                build(
                    "smtp.example.com",
                    465,
                    MailProviderProperties.Security.SSL,
                    SENDER,
                    PASSWORD,
                    SENDER,
                    null,
                    "support@example.com",
                    TEN_SECONDS,
                    THIRTY_SECONDS,
                    THIRTY_SECONDS))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                build(
                    "smtp.example.com",
                    465,
                    MailProviderProperties.Security.SSL,
                    SENDER,
                    PASSWORD,
                    SENDER,
                    null,
                    "support <support@example.com>",
                    TEN_SECONDS,
                    THIRTY_SECONDS,
                    THIRTY_SECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.reply-to");
  }

  @Test
  void 소켓_제한_시간은_밀리초로_1_이상_int_최대값_이하여야_한다() {
    // Angus는 밀리초 int로 받는다. 0으로 잘리면 무제한(setSoTimeout(0))이 되고, int를 넘으면 파싱 실패로 조용히 -1(무제한)이 된다.
    Duration halfMillisecond = Duration.ofNanos(500_000);
    Duration overInt = Duration.ofMillis(Integer.MAX_VALUE).plusMillis(1);
    Duration overLong = Duration.ofSeconds(Long.MAX_VALUE / 1000 + 1);
    for (Duration bad : new Duration[] {halfMillisecond, overInt, overLong}) {
      assertThatThrownBy(() -> withTimeouts(bad, THIRTY_SECONDS, THIRTY_SECONDS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("itda.mail.provider.connect-timeout");
      assertThatThrownBy(() -> withTimeouts(TEN_SECONDS, bad, THIRTY_SECONDS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("itda.mail.provider.read-timeout");
      assertThatThrownBy(() -> withTimeouts(TEN_SECONDS, THIRTY_SECONDS, bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("itda.mail.provider.write-timeout");
    }
    MailProviderProperties boundary =
        withTimeouts(
            Duration.ofMillis(1), Duration.ofMillis(Integer.MAX_VALUE), Duration.ofMillis(1));
    assertThat(boundary.connectTimeout().toMillis()).isEqualTo(1);
    assertThat(boundary.readTimeout().toMillis()).isEqualTo(Integer.MAX_VALUE);
  }

  private static MailProviderProperties withTimeouts(
      Duration connect, Duration read, Duration write) {
    return build(
        "smtp.example.com",
        465,
        MailProviderProperties.Security.SSL,
        SENDER,
        PASSWORD,
        SENDER,
        null,
        null,
        connect,
        read,
        write);
  }

  @Test
  void 연결_읽기_쓰기_제한_시간은_0보다_커야_한다() {
    assertThatThrownBy(
            () ->
                build(
                    "smtp.example.com",
                    465,
                    MailProviderProperties.Security.SSL,
                    SENDER,
                    PASSWORD,
                    SENDER,
                    null,
                    null,
                    Duration.ZERO,
                    THIRTY_SECONDS,
                    THIRTY_SECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.connect-timeout");
    assertThatThrownBy(
            () ->
                build(
                    "smtp.example.com",
                    465,
                    MailProviderProperties.Security.SSL,
                    SENDER,
                    PASSWORD,
                    SENDER,
                    null,
                    null,
                    TEN_SECONDS,
                    Duration.ofSeconds(-1),
                    THIRTY_SECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.read-timeout");
    assertThatThrownBy(
            () ->
                build(
                    "smtp.example.com",
                    465,
                    MailProviderProperties.Security.SSL,
                    SENDER,
                    PASSWORD,
                    SENDER,
                    null,
                    null,
                    TEN_SECONDS,
                    THIRTY_SECONDS,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.mail.provider.write-timeout");
  }
}
