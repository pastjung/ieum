package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.itda.support.log.CapturingAppender;
import com.itda.support.security.StubUserRepository;
import com.itda.support.smtp.ScriptedSmtpServer;
import com.itda.support.smtp.SmtpScript;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.MailDispatchFailureKinds;
import com.itda.user.domain.MailOutboxEntry;
import com.itda.user.domain.User;
import com.itda.user.domain.UserStatus;
import com.itda.user.repository.ActionTokenStore;
import com.itda.user.repository.MailOutboxRepository;
import com.itda.user.repository.UserRepository;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.diagnostics.LoggingFailureAnalysisReporter;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * 발송 실행 조립을 고정한다 — 꺼진 기본값, 켜졌을 때 provider·worker·scheduler가 함께 생기는 것, 켜졌는데 설정이 빠지면 명확히 실패하는 것, 기동
 * 시점에 SMTP 서버에 연결하지 않는 것.
 *
 * <p>worker의 저장소·토큰 의존은 호출되지 않는 대역이다. 조립이 검증 대상이지 처리 흐름이 아니다.
 */
class MailDispatchConfigurationTest {

  private static final String ENABLED = "itda.mail.dispatch.schedule.enabled=true";
  private static final String PASSWORD = "test-smtp-password";
  private static final String[] LINKS = {
    "itda.mail.link.email-verification-url=https://app.example.com/verify-email",
    "itda.mail.link.password-reset-url=https://app.example.com/reset-password"
  };

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(MailDispatchConfiguration.class)
          .withBean(MailOutboxRepository.class, () -> mock(MailOutboxRepository.class))
          .withBean(UserRepository.class, StubUserRepository::new)
          .withBean(ActionTokenStore.class, () -> mock(ActionTokenStore.class))
          .withBean(
              EmailVerificationTokenIssuer.class,
              () ->
                  claims -> {
                    throw new UnsupportedOperationException("조립 테스트에서는 발급하지 않는다.");
                  })
          .withBean(
              PasswordResetTokenIssuer.class,
              () ->
                  claims -> {
                    throw new UnsupportedOperationException("조립 테스트에서는 발급하지 않는다.");
                  })
          .withPropertyValues("spring.profiles.active=test");

  private static String[] plaintextProvider(String host) {
    return new String[] {
      "itda.mail.provider.host=" + host,
      "itda.mail.provider.port=2525",
      "itda.mail.provider.security=PLAINTEXT",
      "itda.mail.provider.from-address=no-reply@example.com"
    };
  }

  /** 빈 outbox를 쓰므로 연결하지 않는다. local·prod에서도 유효한 TLS 설정으로 링크 정책만 검사한다. */
  private static String[] tlsProvider() {
    return new String[] {
      "itda.mail.provider.host=127.0.0.1",
      "itda.mail.provider.port=2525",
      "itda.mail.provider.security=SSL",
      "itda.mail.provider.username=smtp-user@example.com",
      "itda.mail.provider.password=" + PASSWORD,
      "itda.mail.provider.from-address=no-reply@example.com"
    };
  }

  /** 원인 사슬의 message 전부. 기동 실패 로그에 무엇이 실리는지 보는 용도다. */
  private static List<String> messagesOf(Throwable failure) {
    List<String> messages = new ArrayList<>();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current.getMessage() != null) {
        messages.add(current.getMessage());
      }
      if (messages.size() > 16) {
        break;
      }
    }
    return messages;
  }

  /**
   * Boot의 BindFailureAnalyzer는 {@link BindException#getProperty()}의 값을 "Value:"로 출력한다. 그 자리에 비밀번호가
   * 오면 안 된다.
   */
  private static List<BindException> bindExceptionsOf(Throwable failure) {
    List<BindException> found = new ArrayList<>();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof BindException bindException) {
        found.add(bindException);
      }
    }
    return found;
  }

  @Test
  void 기본값은_꺼짐이며_제공자_worker_스케줄러_없이_기동한다() {
    this.runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(
                  context
                      .getBean(MailDispatchConfiguration.MailDispatchEnabledSwitch.class)
                      .enabled())
              .isFalse();
          assertThat(context).hasSingleBean(MailOutboxEnqueueService.class);
          assertThat(context).doesNotHaveBean(MailProvider.class);
          assertThat(context).doesNotHaveBean(MailDispatchWorker.class);
          assertThat(context).doesNotHaveBean(MailDispatchScheduler.class);
          assertThat(context).doesNotHaveBean(MailProviderProperties.class);
          assertThat(context).doesNotHaveBean(MailLinkProperties.class);
          assertThat(context).doesNotHaveBean(MailDispatchScheduleProperties.class);
        });
  }

  @Test
  void 꺼져_있으면_제공자_설정이_틀리거나_없어도_기동한다() {
    this.runner
        .withPropertyValues(
            "itda.mail.dispatch.schedule.enabled=false", "itda.mail.provider.port=0")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(MailProvider.class);
              assertThat(context).doesNotHaveBean(MailDispatchScheduler.class);
            });
  }

  @Test
  void 켜면_제공자_worker_스케줄러를_함께_조립하고_스케줄러가_실행_중이며_기본값이_바인딩된다() {
    this.runner
        .withPropertyValues(ENABLED)
        .withPropertyValues(plaintextProvider("127.0.0.1"))
        .withPropertyValues(LINKS)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(SmtpMailProvider.class);
              assertThat(context).hasSingleBean(MailDispatchWorker.class);
              assertThat(context).hasSingleBean(MailDispatchScheduler.class);
              assertThat(context.getBean(MailDispatchScheduler.class).isRunning()).isTrue();

              MailDispatchScheduleProperties schedule =
                  context.getBean(MailDispatchScheduleProperties.class);
              assertThat(schedule.pollInterval()).isEqualTo(Duration.ofSeconds(10));
              assertThat(schedule.cleanupInterval()).isEqualTo(Duration.ofMinutes(5));
              assertThat(schedule.batchSize()).isEqualTo(20);
              assertThat(schedule.shutdownWait()).isEqualTo(Duration.ofSeconds(30));

              MailProviderProperties provider = context.getBean(MailProviderProperties.class);
              assertThat(provider.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
              assertThat(provider.readTimeout()).isEqualTo(Duration.ofSeconds(30));
              assertThat(provider.writeTimeout()).isEqualTo(Duration.ofSeconds(30));
              assertThat(provider.hasCredentials()).isFalse();
            });
  }

  @Test
  void 켜졌는데_제공자_host가_없으면_기동에_실패하고_실패_원인_어디에도_비밀번호가_없다() {
    this.runner
        .withPropertyValues(ENABLED)
        .withPropertyValues(
            "itda.mail.provider.port=465",
            "itda.mail.provider.security=SSL",
            "itda.mail.provider.username=smtp-user@example.com",
            "itda.mail.provider.password=" + PASSWORD,
            "itda.mail.provider.from-address=no-reply@example.com")
        .withPropertyValues(LINKS)
        .run(
            context -> {
              assertThat(context).hasFailed();
              Throwable failure = context.getStartupFailure();
              List<String> messages = messagesOf(failure);
              assertThat(messages).anyMatch(m -> m.contains("itda.mail.provider.host"));
              assertThat(messages).allMatch(m -> !m.contains(PASSWORD));
              assertThat(bindExceptionsOf(failure))
                  .allSatisfy(
                      bind -> {
                        if (bind.getProperty() != null) {
                          assertThat(bind.getProperty().getName().toString())
                              .doesNotContain("password");
                          assertThat(String.valueOf(bind.getProperty().getValue()))
                              .doesNotContain(PASSWORD);
                        }
                      });
            });
  }

  @Test
  void 켜졌는데_링크_주소가_없으면_기동에_실패한다() {
    this.runner
        .withPropertyValues(ENABLED)
        .withPropertyValues(plaintextProvider("127.0.0.1"))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(messagesOf(context.getStartupFailure()))
                  .anyMatch(m -> m.contains("itda.mail.link"));
            });
  }

  @Test
  void PLAINTEXT는_test_실행_환경에서만_허용한다() {
    this.runner
        .withPropertyValues("spring.profiles.active=prod")
        .withPropertyValues(ENABLED)
        .withPropertyValues(plaintextProvider("127.0.0.1"))
        .withPropertyValues(LINKS)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(messagesOf(context.getStartupFailure()))
                  .anyMatch(m -> m.contains("PLAINTEXT"));
            });
  }

  @ParameterizedTest
  @CsvSource({
    "local, http://localhost:3000",
    "local, http://127.0.0.1:3000",
    "local, HTTP://[::1]:3000",
    "test, http://localhost:3000",
    "test, http://127.0.0.1:3000",
    "test, HTTP://[::1]:3000"
  })
  void local_test에서는_루프백_HTTP_링크를_허용한다(String profile, String base) {
    this.runner
        .withPropertyValues(ENABLED, "spring.profiles.active=" + profile)
        .withPropertyValues(tlsProvider())
        .withPropertyValues(
            "itda.mail.link.email-verification-url=" + base + "/verify-email",
            "itda.mail.link.password-reset-url=" + base + "/reset-password")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(SmtpMailProvider.class);
              assertThat(context.getBean(MailDispatchScheduler.class).isRunning()).isTrue();
            });
  }

  @ParameterizedTest
  @CsvSource({
    "email-verification-url, http://localhost:3000/verify-email",
    "email-verification-url, http://127.0.0.1:3000/verify-email",
    "email-verification-url, HTTP://[::1]:3000/verify-email",
    "password-reset-url, http://localhost:3000/reset-password",
    "password-reset-url, http://127.0.0.1:3000/reset-password",
    "password-reset-url, HTTP://[::1]:3000/reset-password"
  })
  void prod에서는_어느_한쪽이라도_HTTP_링크면_기동을_거부한다(String setting, String url) {
    this.runner
        .withPropertyValues(ENABLED, "spring.profiles.active=prod")
        .withPropertyValues(tlsProvider())
        .withPropertyValues(LINKS)
        .withPropertyValues("itda.mail.link." + setting + "=" + url)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(messagesOf(context.getStartupFailure()))
                  .anyMatch(
                      message -> message.contains("itda.mail.link") && message.contains("https"))
                  .allMatch(message -> !message.contains(url) && !message.contains(PASSWORD));
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"local", "test", "prod"})
  void HTTPS_링크는_모든_실행_환경에서_허용한다(String profile) {
    this.runner
        .withPropertyValues(ENABLED, "spring.profiles.active=" + profile)
        .withPropertyValues(tlsProvider())
        .withPropertyValues(LINKS)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"local,prod", "prod,test"})
  void 개발_프로필을_함께_켜도_prod의_HTTP_제약을_우회할_수_없다(String profiles) {
    this.runner
        .withPropertyValues(ENABLED, "spring.profiles.active=" + profiles)
        .withPropertyValues(tlsProvider())
        .withPropertyValues(
            "itda.mail.link.email-verification-url=http://localhost:3000/verify-email",
            "itda.mail.link.password-reset-url=http://localhost:3000/reset-password")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(messagesOf(context.getStartupFailure()))
                  .anyMatch(message -> message.contains("프로필"));
            });
  }

  @Test
  void prod라도_발송이_꺼져_있으면_HTTP_링크를_검증하거나_제공자를_만들지_않는다() {
    this.runner
        .withPropertyValues(
            "spring.profiles.active=prod", "itda.mail.dispatch.schedule.enabled=false")
        .withPropertyValues(
            "itda.mail.link.email-verification-url=http://localhost:3000/verify-email")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(MailLinkProperties.class);
              assertThat(context).doesNotHaveBean(SmtpMailProvider.class);
              assertThat(context).doesNotHaveBean(MailDispatchScheduler.class);
            });
  }

  @Test
  void PLAINTEXT는_루프백_host에서만_허용한다() {
    this.runner
        .withPropertyValues(ENABLED)
        .withPropertyValues(plaintextProvider("smtp.example.com"))
        .withPropertyValues(LINKS)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(messagesOf(context.getStartupFailure()))
                  .anyMatch(m -> m.contains("PLAINTEXT"));
            });
  }

  @Test
  void 활성화_값이_true_false가_아니면_조용히_끄지_않고_기동에_실패한다() {
    // 앞뒤 공백은 TestPropertyValues가 잘라 버려 여기서는 재현할 수 없다. 운영 코드는 trim하지 않으므로 "TRUE "도 거부한다.
    for (String value : new String[] {"yes", "on", "1", "enabled"}) {
      this.runner
          .withPropertyValues("itda.mail.dispatch.schedule.enabled=" + value)
          .withPropertyValues(plaintextProvider("127.0.0.1"))
          .withPropertyValues(LINKS)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(messagesOf(context.getStartupFailure()))
                    .anyMatch(m -> m.contains("itda.mail.dispatch.schedule.enabled"));
              });
    }
    this.runner
        .withPropertyValues("itda.mail.dispatch.schedule.enabled=FALSE")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @ParameterizedTest
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\r\n", " true", "true ", " false", "false "})
  void 명시적인_빈_값과_공백을_포함한_활성화_값은_기동에_실패한다(String value) {
    withRawEnabledValue(value)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalStateException.class)
                  .hasRootCauseMessage(
                      MailDispatchConfiguration.ENABLED_PROPERTY
                          + "은(는) true 또는 false여야 합니다. 다른 값은 꺼짐으로 취급하지 않고 기동을 실패시킵니다.");
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "TRUE", "TrUe", "false", "FALSE", "FaLsE"})
  void 활성화_값의_대소문자를_허용하고_꺼짐에는_SMTP_자격증명이_필요하지_않다(String value) {
    boolean enabled = Boolean.parseBoolean(value);
    ApplicationContextRunner configured = withRawEnabledValue(value);
    if (enabled) {
      configured =
          configured.withPropertyValues(plaintextProvider("127.0.0.1")).withPropertyValues(LINKS);
    }
    configured.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(
                  context
                      .getBean(MailDispatchConfiguration.MailDispatchEnabledSwitch.class)
                      .enabled())
              .isEqualTo(enabled);
          if (enabled) {
            assertThat(context).hasSingleBean(SmtpMailProvider.class);
            assertThat(context).hasSingleBean(MailDispatchWorker.class);
            assertThat(context.getBean(MailDispatchScheduler.class).isRunning()).isTrue();
          } else {
            assertThat(context).doesNotHaveBean(MailProviderProperties.class);
            assertThat(context).doesNotHaveBean(SmtpMailProvider.class);
            assertThat(context).doesNotHaveBean(MailDispatchWorker.class);
            assertThat(context).doesNotHaveBean(MailDispatchScheduler.class);
          }
        });
  }

  /** TestPropertyValues의 trim을 거치지 않고 운영 코드에 원문을 전달한다. */
  private ApplicationContextRunner withRawEnabledValue(String value) {
    return this.runner.withInitializer(
        context -> {
          context
              .getEnvironment()
              .getPropertySources()
              .addFirst(
                  new MapPropertySource(
                      "raw-mail-dispatch-switch",
                      Map.of(MailDispatchConfiguration.ENABLED_PROPERTY, value)));
          assertThat(
                  context.getEnvironment().getProperty(MailDispatchConfiguration.ENABLED_PROPERTY))
              .isEqualTo(value);
        });
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t", " true", "false ", "yes", "true", "TRUE", "false", "FALSE"})
  void 실제_YAML의_활성화_매핑은_미설정만_기본값으로_해석하고_빈_입력을_보존한다(String value) throws IOException {
    // 공통 YAML만 로드한다. 프로필 import나 실제 환경변수·개인 설정을 읽지 않는다.
    MockEnvironment environment = new MockEnvironment();
    if (value != null) {
      environment.setProperty("MAIL_DISPATCH_ENABLED", value);
    }
    environment
        .getPropertySources()
        .addLast(
            new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0));
    String resolved = value == null ? "false" : value;
    assertThat(environment.getProperty(MailDispatchConfiguration.ENABLED_PROPERTY))
        .isEqualTo(resolved);

    MailDispatchConfiguration configuration = new MailDispatchConfiguration();
    if ("true".equalsIgnoreCase(resolved) || "false".equalsIgnoreCase(resolved)) {
      assertThat(configuration.mailDispatchEnabledSwitch(environment).enabled())
          .isEqualTo(Boolean.parseBoolean(resolved));
    } else {
      assertThatThrownBy(() -> configuration.mailDispatchEnabledSwitch(environment))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              MailDispatchConfiguration.ENABLED_PROPERTY
                  + "은(는) true 또는 false여야 합니다. 다른 값은 꺼짐으로 취급하지 않고 기동을 실패시킵니다.");
    }
  }

  /**
   * "기동 시 SMTP 연결 없음"은 기동 검증용 연결 점검을 하지 않는다는 뜻이다. outbox에 보낼 작업이 있으면 기동 직후 첫 poll이 실제 발송을 시도한다 — 그
   * 사실은 아래 종료 테스트가 보여 준다.
   */
  @Test
  void 켜져도_기동_검증용_SMTP_연결_점검을_하지_않는다_outbox가_비어_있으면_연결이_없다() throws Exception {
    try (ScriptedSmtpServer server = ScriptedSmtpServer.start(SmtpScript.standard())) {
      this.runner
          .withPropertyValues("spring.profiles.active=prod")
          .withPropertyValues(ENABLED)
          .withPropertyValues(
              "itda.mail.provider.host=" + server.host(),
              "itda.mail.provider.port=" + server.port(),
              "itda.mail.provider.security=STARTTLS",
              "itda.mail.provider.username=smtp-user@example.com",
              "itda.mail.provider.password=" + PASSWORD,
              "itda.mail.provider.from-address=no-reply@example.com",
              "itda.mail.dispatch.schedule.poll-interval=1s")
          .withPropertyValues(LINKS)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(MailDispatchScheduler.class).isRunning()).isTrue();
                // 첫 poll은 즉시 돈다. 보낼 작업이 없으면 제공자에 연결할 이유가 없다.
                Thread.sleep(300);
                assertThat(server.connectionCount()).isZero();
              });
      assertThat(server.connectionCount()).isZero();
    }
  }

  /**
   * 기동 직후 첫 poll이 보낸 메일의 응답을 서버가 보류한 채 context가 닫힌다. 종료 순서(스케줄러 정지 → 연결 중단 → 스레드 종료 → 제공자 종료)가 실제로
   * 지켜지고, 접수 여부가 불명확한 호출은 {@code UNCERTAIN}으로 기록되어야 한다.
   */
  @Test
  void 본문_전송_뒤_응답이_보류된_채_context가_닫히면_연결을_끊고_UNCERTAIN으로_기록한_뒤_스레드_없이_종료한다() throws Exception {
    try (ScriptedSmtpServer server =
        ScriptedSmtpServer.start(
            SmtpScript.standard().withoutAuth().hangAt(SmtpScript.Step.END_OF_DATA))) {
      MailOutboxRepository outbox = pendingOutbox();
      new ApplicationContextRunner()
          .withUserConfiguration(MailDispatchConfiguration.class)
          .withBean(MailOutboxRepository.class, () -> outbox)
          .withBean(UserRepository.class, MailDispatchConfigurationTest::activeUnverifiedUser)
          .withBean(
              ActionTokenStore.class,
              () -> {
                ActionTokenStore store = mock(ActionTokenStore.class);
                when(store.issueActionToken(any())).thenReturn(ActionTokenStore.IssueResult.ISSUED);
                return store;
              })
          .withBean(
              EmailVerificationTokenIssuer.class,
              () -> claims -> issuedToken(AccountActionPurpose.EMAIL_VERIFICATION))
          .withBean(
              PasswordResetTokenIssuer.class,
              () -> claims -> issuedToken(AccountActionPurpose.PASSWORD_RESET))
          .withPropertyValues("spring.profiles.active=test")
          .withPropertyValues(ENABLED)
          .withPropertyValues(
              "itda.mail.provider.host=" + server.host(),
              "itda.mail.provider.port=" + server.port(),
              "itda.mail.provider.security=PLAINTEXT",
              "itda.mail.provider.from-address=no-reply@example.com",
              "itda.mail.provider.read-timeout=30s",
              "itda.mail.dispatch.schedule.shutdown-wait=1s")
          .withPropertyValues(LINKS)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                String watchdog = context.getBean(SmtpMailProvider.class).watchdogThreadName();
                ScriptedSmtpServer.Transcript transcript =
                    server.awaitConnection(0, Duration.ofSeconds(5));
                awaitTrue(() -> transcript.messages().size() == 1, "본문 수신");

                long begun = System.nanoTime();
                context.close();
                Duration elapsed = Duration.ofNanos(System.nanoTime() - begun);

                // shutdown-wait(1s) + 중단 뒤 확인 유예 안에 끝난다. 소켓 read timeout(30s)이나
                // provider-timeout(60s)이 아니다.
                assertThat(elapsed).isLessThan(Duration.ofSeconds(8));
                assertThat(server.awaitFinished(0, Duration.ofSeconds(5)).peerClosed()).isTrue();
                verify(outbox)
                    .markRetry(
                        any(), any(), eq(MailDispatchFailureKinds.PROVIDER_UNCERTAIN), any());
                assertThat(threadsNamed("mail-dispatch-")).isEmpty();
                assertThat(threadsNamed(watchdog)).isEmpty();
              });
    }
  }

  /**
   * 기동 실패의 <b>실제 출력</b>을 검사한다. Boot는 실패를 FailureAnalyzer 보고 또는 "Application run failed" 스택 트레이스로
   * 기록하는데, 둘 중 어느 경로에도 비밀번호가 실리면 안 된다. 예외 객체 검사(위 테스트)와 별개다.
   */
  @Test
  void 기동_실패의_실제_로그_출력에_비밀번호가_없다() {
    // Boot의 LoggingApplicationListener가 기동 중 logback을 다시 초기화해 미리 붙인 appender를 떼어 낸다.
    // 그래서 환경 준비 이벤트(로깅 초기화 뒤) 시점에 붙인다.
    LogCaptureAfterLoggingInit capture = new LogCaptureAfterLoggingInit();
    try {
      SpringApplication app =
          new SpringApplicationBuilder(MailDispatchConfiguration.class, StubDependencies.class)
              .web(WebApplicationType.NONE)
              .bannerMode(Banner.Mode.OFF)
              .registerShutdownHook(false)
              .listeners(capture)
              .build();
      // application.yml(${MAIL_...} 매핑)보다 우선해야 하므로 default properties가 아니라 명령줄 인자로 준다.
      String[] args = {
        "--spring.profiles.active=test",
        "--" + ENABLED,
        "--itda.mail.provider.port=465",
        "--itda.mail.provider.security=SSL",
        "--itda.mail.provider.username=smtp-user@example.com",
        "--itda.mail.provider.password=" + PASSWORD,
        "--itda.mail.provider.from-address=no-reply@example.com",
        "--itda.mail.link.email-verification-url=https://app.example.com/verify-email",
        "--itda.mail.link.password-reset-url=https://app.example.com/reset-password"
      };

      Throwable failure = catchThrowable(() -> app.run(args));

      assertThat(failure).isNotNull();
      List<String> output = new ArrayList<>();
      for (ILoggingEvent event : capture.events()) {
        output.add(event.getFormattedMessage());
        output.addAll(throwableMessages(event));
      }
      assertThat(output).isNotEmpty();
      assertThat(output).anyMatch(line -> line.contains("itda.mail.provider"));
      assertThat(output).allMatch(line -> !line.contains(PASSWORD));
    } finally {
      capture.detach();
    }
  }

  /** 로깅 초기화가 끝난 환경 준비 시점에 실패 보고 logger와 SpringApplication logger를 잡는다. */
  static final class LogCaptureAfterLoggingInit
      implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {
    private final List<CapturingAppender> appenders = new ArrayList<>();

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
      this.appenders.add(CapturingAppender.attachTo(LoggingFailureAnalysisReporter.class));
      this.appenders.add(CapturingAppender.attachTo(SpringApplication.class));
    }

    List<ILoggingEvent> events() {
      List<ILoggingEvent> events = new ArrayList<>();
      for (CapturingAppender appender : this.appenders) {
        events.addAll(appender.events());
      }
      return events;
    }

    void detach() {
      this.appenders.forEach(CapturingAppender::detach);
    }
  }

  private static List<String> throwableMessages(ILoggingEvent event) {
    List<String> messages = new ArrayList<>();
    for (IThrowableProxy proxy = event.getThrowableProxy();
        proxy != null;
        proxy = proxy.getCause()) {
      messages.add(String.valueOf(proxy.getMessage()));
    }
    return messages;
  }

  private static MailOutboxRepository pendingOutbox() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    MailOutboxEntry entry = mock(MailOutboxEntry.class);
    when(entry.getId()).thenReturn(1L);
    when(entry.getPurpose()).thenReturn(AccountActionPurpose.EMAIL_VERIFICATION);
    when(entry.getRecipientEmail()).thenReturn("learner-shutdown@example.com");
    when(entry.getAttemptCount()).thenReturn(0);
    when(entry.getNextAttemptAt()).thenReturn(now);
    when(entry.getDeliveryDeadlineAt()).thenReturn(now.plusHours(1));
    MailOutboxRepository outbox = mock(MailOutboxRepository.class);
    when(outbox.findClaimCandidates(any(), anyInt(), anyInt()))
        .thenReturn(List.of(entry))
        .thenReturn(List.of());
    when(outbox.claim(any(), any(), anyInt())).thenReturn(true);
    when(outbox.markRetry(any(), any(), any(), any())).thenReturn(true);
    return outbox;
  }

  private static UserRepository activeUnverifiedUser() {
    User user = mock(User.class);
    when(user.getId()).thenReturn(42L);
    when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
    when(user.getEmailVerifiedAt()).thenReturn(null);
    UserRepository users = mock(UserRepository.class);
    when(users.findByEmail(any())).thenReturn(Optional.of(user));
    return users;
  }

  private static IssuedActionToken issuedToken(AccountActionPurpose purpose) {
    long now = Instant.now().getEpochSecond();
    String compact = "header.payload." + UUID.randomUUID();
    return new IssuedActionToken(
        compact,
        CompactTokenHasher.hash(compact),
        UUID.randomUUID().toString(),
        purpose,
        now,
        now + 3600);
  }

  private static List<String> threadsNamed(String prefix) {
    return Thread.getAllStackTraces().keySet().stream()
        .map(Thread::getName)
        .filter(name -> name.startsWith(prefix))
        .toList();
  }

  private static void awaitTrue(BooleanSupplier condition, String what)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(what + " 조건이 제한 시간 안에 충족되지 않았습니다.");
      }
      Thread.sleep(10);
    }
  }

  /** 기동 실패 출력 테스트용 의존 bean. worker 조립에만 필요하고 호출되지 않는다. */
  @Configuration(proxyBeanMethods = false)
  static class StubDependencies {
    @Bean
    MailOutboxRepository mailOutboxRepository() {
      return mock(MailOutboxRepository.class);
    }

    @Bean
    UserRepository userRepository() {
      return new StubUserRepository();
    }

    @Bean
    ActionTokenStore actionTokenStore() {
      return mock(ActionTokenStore.class);
    }

    @Bean
    EmailVerificationTokenIssuer emailVerificationTokenIssuer() {
      return claims -> {
        throw new UnsupportedOperationException();
      };
    }

    @Bean
    PasswordResetTokenIssuer passwordResetTokenIssuer() {
      return claims -> {
        throw new UnsupportedOperationException();
      };
    }
  }
}
