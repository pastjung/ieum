package com.itda.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusUtil;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code logback-spring.xml}이 콘솔 출력 경계에 마스킹 encoder를 연결하는지 고정한다.
 *
 * <p>설정 파일에서 encoder class가 빠지거나 다른 appender가 root에 붙으면 가려지지 않은 원문이 나가므로, 실제 설정을 읽어 출력까지 확인한다.
 */
class LogbackSpringConfigurationTest {

  private static String synthetic(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }

  @Test
  void root의_유일한_콘솔_appender가_마스킹_encoder로_출력한다() throws Exception {
    LoggerContext context = new LoggerContext();
    try {
      URL configuration = getClass().getClassLoader().getResource("logback-spring.xml");
      assertThat(configuration).isNotNull();
      JoranConfigurator configurator = new JoranConfigurator();
      configurator.setContext(context);
      configurator.doConfigure(configuration);

      assertThat(new StatusUtil(context).getHighestLevel(0)).isLessThan(Status.ERROR);
      Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
      assertThat(root.getLevel()).isEqualTo(Level.INFO);
      assertThat(root.iteratorForAppenders()).toIterable().hasSize(1);
      Appender<ILoggingEvent> appender = root.getAppender("CONSOLE");
      assertThat(appender).isInstanceOf(ConsoleAppender.class);
      Encoder<ILoggingEvent> encoder = ((ConsoleAppender<ILoggingEvent>) appender).getEncoder();
      assertThat(encoder).isInstanceOf(SensitiveDataMaskingEncoder.class);

      String user = synthetic("dbu");
      String secret = synthetic("pw");
      LoggingEvent event =
          new LoggingEvent(
              Logger.class.getName(),
              context.getLogger("org.hibernate.orm.connections.pooling"),
              Level.INFO,
              "Database info:\n\tDatabase JDBC URL [jdbc:mariadb://"
                  + synthetic("h")
                  + "/db?user="
                  + user
                  + "&password="
                  + secret
                  + "]",
              null,
              null);
      // Boot가 앞선 테스트에서 correlation pattern(%X{requestId})을 시스템 속성으로 남기면 이 설정도 MDC를 읽는다.
      event.setMDCPropertyMap(Map.of("requestId", UUID.randomUUID().toString()));
      String output = new String(encoder.encode(event), StandardCharsets.UTF_8);

      assertThat(output.contains(user)).as("DB 사용자 원문이 남지 않는다").isFalse();
      assertThat(output.contains(secret)).as("DB 비밀번호 원문이 남지 않는다").isFalse();
      assertThat(output)
          .contains(" INFO ")
          .contains("org.hibernate.orm.connections.pooling")
          .contains("Database JDBC URL [REDACTED]");
      assertThat(output.contains("[REDACTED]]")).as("대괄호가 중복되지 않는다").isFalse();
    } finally {
      context.stop();
    }
  }
}
