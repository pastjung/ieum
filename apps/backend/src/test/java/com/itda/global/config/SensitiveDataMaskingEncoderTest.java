package com.itda.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * pattern이 조합한 최종 출력에서 등록된 민감정보 형식이 가려지는지, 마스킹 처리 중 예외가 발생하거나 결과가 null이면 원문 대신 고정 문구가 나가는지 고정한다.
 *
 * <p>원문은 실행마다 합성하고 포함 여부는 boolean으로만 단언한다.
 */
class SensitiveDataMaskingEncoderTest {

  private static final String PATTERN = "%level %logger [%X{requestId}] %msg%n%ex";

  private LoggerContext context;

  @BeforeEach
  void setUp() {
    this.context = new LoggerContext();
    // 실제 Logback 초기화처럼 MDC adapter를 둔다. requestId가 없는 로그는 빈 MDC를 읽는다.
    this.context.setMDCAdapter(new LogbackMDCAdapter());
  }

  @AfterEach
  void tearDown() {
    this.context.stop();
  }

  private static String synthetic(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }

  private static void assertHidden(String output, String original, String description) {
    assertThat(output.contains(original)).as(description + " 원문이 남지 않는다").isFalse();
  }

  private SensitiveDataMaskingEncoder started(SensitiveDataMaskingEncoder encoder) {
    encoder.setContext(this.context);
    encoder.setPattern(PATTERN);
    encoder.setCharset(StandardCharsets.UTF_8);
    encoder.start();
    return encoder;
  }

  private LoggingEvent event(
      String loggerName, Level level, String message, Throwable throwable, String requestId) {
    Logger logger = this.context.getLogger(loggerName);
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), logger, level, message, throwable, null);
    event.setMDCPropertyMap(Map.of("requestId", requestId));
    return event;
  }

  private LoggingEvent eventWithoutRequestId(String loggerName, Level level, String message) {
    return new LoggingEvent(
        Logger.class.getName(), this.context.getLogger(loggerName), level, message, null, null);
  }

  private static String encode(SensitiveDataMaskingEncoder encoder, LoggingEvent event) {
    return new String(encoder.encode(event), StandardCharsets.UTF_8);
  }

  @Test
  void 메시지의_식별자는_가리고_level_logger_requestId는_남긴다() {
    String email = synthetic("u") + "@" + "example" + ".invalid";
    String requestId = UUID.randomUUID().toString();

    String output =
        encode(
            started(new SensitiveDataMaskingEncoder()),
            event(
                "org.hibernate.orm.jdbc.error",
                Level.WARN,
                "(conn=6) Duplicate entry '" + email + "' for key 'UK_email'",
                null,
                requestId));

    assertHidden(output, email, "email");
    assertThat(output)
        .isEqualTo(
            "WARN org.hibernate.orm.jdbc.error ["
                + requestId
                + "] (conn=6) Duplicate entry '[REDACTED]' for key 'UK_email'"
                + System.lineSeparator());
  }

  @Test
  void 예외_메시지와_원인_체인에_담긴_민감정보도_가리고_stack_trace는_남긴다() {
    String user = synthetic("dbu");
    String secret = synthetic("pw");
    String username = synthetic("user");
    SQLException cause =
        new SQLException(
            "Cannot connect to jdbc:mariadb://"
                + synthetic("h")
                + "/db?user="
                + user
                + "&password="
                + secret);
    IllegalStateException thrown =
        new IllegalStateException(
            "could not execute statement [Duplicate entry '" + username + "' for key 'UK_name']",
            cause);

    String output =
        encode(
            started(new SensitiveDataMaskingEncoder()),
            event(
                "org.hibernate.orm.jdbc.error",
                Level.ERROR,
                "insert failed",
                thrown,
                UUID.randomUUID().toString()));

    assertHidden(output, user, "DB 사용자");
    assertHidden(output, secret, "DB 비밀번호");
    assertHidden(output, username, "username");
    assertThat(output)
        .contains("java.lang.IllegalStateException")
        .contains("Duplicate entry '[REDACTED]' for key 'UK_name'")
        .contains("Caused by: java.sql.SQLException: Cannot connect to jdbc:mariadb:[REDACTED]")
        .contains("\tat ");
  }

  @Test
  void 마스킹이_예외로_실패하면_원문_대신_고정_문구와_진단_정보만_내보낸다() {
    String secret = synthetic("pw");
    String requestId = UUID.randomUUID().toString();
    SensitiveDataMaskingEncoder encoder =
        started(
            new SensitiveDataMaskingEncoder(
                text -> {
                  throw new IllegalStateException("masking failure");
                }));

    String output =
        encode(
            encoder,
            event("com.itda.example.Worker", Level.INFO, "password=" + secret, null, requestId));

    assertHidden(output, secret, "비밀번호");
    assertThat(output)
        .isEqualTo(
            "INFO com.itda.example.Worker ["
                + requestId
                + "] "
                + SensitiveDataMaskingEncoder.WITHHELD
                + System.lineSeparator());
  }

  @Test
  void 마스킹_결과가_없으면_원문_대신_고정_문구를_내보낸다() {
    String secret = synthetic("pw");

    String output =
        encode(
            started(new SensitiveDataMaskingEncoder(text -> null)),
            event(
                "com.itda.example.Worker",
                Level.WARN,
                "password=" + secret,
                null,
                UUID.randomUUID().toString()));

    assertHidden(output, secret, "비밀번호");
    assertThat(output).contains(SensitiveDataMaskingEncoder.WITHHELD);
  }

  @Test
  void requestId가_없는_로그에서_마스킹이_예외로_실패해도_고정_문구만_내보낸다() {
    String secret = synthetic("pw");
    SensitiveDataMaskingEncoder encoder =
        started(
            new SensitiveDataMaskingEncoder(
                text -> {
                  throw new IllegalStateException("masking failure");
                }));
    LoggingEvent event =
        eventWithoutRequestId("com.itda.example.Worker", Level.INFO, "pass" + "word=" + secret);

    assertThatCode(() -> encoder.encode(event)).doesNotThrowAnyException();
    String output = encode(encoder, event);

    assertHidden(output, secret, "비밀번호");
    assertThat(output)
        .isEqualTo(
            "INFO com.itda.example.Worker "
                + SensitiveDataMaskingEncoder.WITHHELD
                + System.lineSeparator());
  }

  @Test
  void requestId가_없는_로그에서_마스킹_결과가_없어도_고정_문구만_내보낸다() {
    String secret = synthetic("pw");
    SensitiveDataMaskingEncoder encoder = started(new SensitiveDataMaskingEncoder(text -> null));
    LoggingEvent event =
        eventWithoutRequestId("com.itda.example.Worker", Level.WARN, "pass" + "word=" + secret);

    assertThatCode(() -> encoder.encode(event)).doesNotThrowAnyException();
    String output = encode(encoder, event);

    assertHidden(output, secret, "비밀번호");
    assertThat(output)
        .isEqualTo(
            "WARN com.itda.example.Worker "
                + SensitiveDataMaskingEncoder.WITHHELD
                + System.lineSeparator());
  }

  @Test
  void layout이_준비되지_않은_encoder도_원문_대신_고정_문구와_진단_정보만_내보낸다() {
    String secretLeft = synthetic("pl");
    String secretRight = synthetic("pr");
    String user = synthetic("dbu");
    String requestId = UUID.randomUUID().toString();
    SensitiveDataMaskingEncoder encoder = new SensitiveDataMaskingEncoder();
    encoder.setContext(this.context);
    LoggingEvent event =
        event(
            "com.itda.example.Worker",
            Level.ERROR,
            "pass" + "word=" + secretLeft + "&" + secretRight,
            new IllegalStateException(
                "Cannot connect to jdbc:mariadb://h/db?user=" + user,
                new SQLException("Duplicate entry '" + secretRight + "' for key 'k'")),
            requestId);

    assertThatCode(() -> encoder.encode(event)).doesNotThrowAnyException();
    String output = encode(encoder, event);

    assertHidden(output, secretLeft, "비밀번호 왼쪽 조각");
    assertHidden(output, secretRight, "비밀번호 오른쪽 조각");
    assertHidden(output, user, "DB 사용자");
    assertThat(output.contains("IllegalStateException")).as("예외 정보가 남지 않는다").isFalse();
    assertThat(output)
        .isEqualTo(
            "ERROR com.itda.example.Worker ["
                + requestId
                + "] "
                + SensitiveDataMaskingEncoder.WITHHELD
                + System.lineSeparator());
  }

  @Test
  void layout_포맷팅이_예외로_실패해도_원문_대신_고정_문구만_내보낸다() {
    String secret = synthetic("pw");
    SensitiveDataMaskingEncoder encoder = started(new SensitiveDataMaskingEncoder());
    // MDC adapter가 없는 context의 event는 %X 변환에서 예외가 난다. requestId도 읽을 수 없는 상황이다.
    LoggerContext withoutMdcAdapter = new LoggerContext();
    try {
      LoggingEvent event =
          new LoggingEvent(
              Logger.class.getName(),
              withoutMdcAdapter.getLogger("com.itda.example.Worker"),
              Level.WARN,
              "pass" + "word=" + secret,
              null,
              null);

      assertThatCode(() -> encoder.encode(event)).doesNotThrowAnyException();
      String output = encode(encoder, event);

      assertHidden(output, secret, "비밀번호");
      assertThat(output)
          .isEqualTo(
              "WARN com.itda.example.Worker "
                  + SensitiveDataMaskingEncoder.WITHHELD
                  + System.lineSeparator());
    } finally {
      withoutMdcAdapter.stop();
    }
  }
}
