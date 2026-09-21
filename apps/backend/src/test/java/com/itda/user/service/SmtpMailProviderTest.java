package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.itda.support.log.CapturingAppender;
import com.itda.support.smtp.ScriptedSmtpServer;
import com.itda.support.smtp.SmtpScript;
import com.itda.support.smtp.SmtpScript.Step;
import com.itda.support.smtp.TestTlsMaterial;
import com.itda.user.domain.AccountActionPurpose;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * SMTP 제공자 adapter를 진짜 소켓·진짜 TLS의 대본형 서버에 대고 검증한다.
 *
 * <p>여기서 고정하는 것은 다섯 결과의 분류 기준(어느 단계에서 무엇을 확인했는가), 전체 제한 시간이 실제로 연결을 끊고 호출을 끝내는지, 반복 실패 뒤 스레드·소켓이 남지
 * 않는지, 로그에 수신 주소·토큰·자격증명이 없는지다. 외부 네트워크로 나가는 연결은 없다.
 */
class SmtpMailProviderTest {

  private static final Instant NOW = Instant.parse("2026-09-16T03:00:00Z");
  private static final String RECIPIENT = "learner@example.com";
  private static final String SENDER = "no-reply@example.com";
  private static final String USERNAME = "smtp-user@example.com";
  private static final String PASSWORD = "test-smtp-password";
  private static final String TOKEN = "test-header.test-payload.test-signature";
  private static final String VERIFY_URL = "https://app.example.com/verify-email";
  private static final String RESET_URL = "https://app.example.com/reset-password";
  private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(5);

  @TempDir static Path tlsDirectory;
  private static TestTlsMaterial tls;

  private ScriptedSmtpServer server;
  private SmtpMailProvider provider;
  private CapturingAppender logs;

  @BeforeAll
  static void generateTlsMaterial() throws Exception {
    tls = TestTlsMaterial.generate(tlsDirectory);
  }

  @BeforeEach
  void captureLogs() {
    this.logs = CapturingAppender.attachTo(SmtpMailProvider.class);
  }

  @AfterEach
  void tearDown() {
    this.logs.detach();
    if (this.provider != null) {
      this.provider.close();
    }
    if (this.server != null) {
      this.server.close();
    }
  }

  // ---------- 준비 ----------

  private static MailLinkProperties links() {
    return new MailLinkProperties(VERIFY_URL, RESET_URL);
  }

  private static MailProviderProperties properties(
      int port, MailProviderProperties.Security security, boolean credentials, String replyTo) {
    return new MailProviderProperties(
        "127.0.0.1",
        port,
        security,
        credentials ? USERNAME : null,
        credentials ? PASSWORD : null,
        SENDER,
        "이음(I:EUM)",
        replyTo,
        SOCKET_TIMEOUT,
        SOCKET_TIMEOUT,
        SOCKET_TIMEOUT);
  }

  private SmtpMailProvider plainProvider(ScriptedSmtpServer server) {
    return newProvider(server, MailProviderProperties.Security.PLAINTEXT, true, null);
  }

  private SmtpMailProvider newProvider(
      ScriptedSmtpServer server,
      MailProviderProperties.Security security,
      boolean credentials,
      String replyTo) {
    this.provider =
        new SmtpMailProvider(
            properties(server.port(), security, credentials, replyTo),
            links(),
            PROVIDER_TIMEOUT,
            Clock.fixed(NOW, ZoneOffset.UTC));
    return this.provider;
  }

  /**
   * 테스트 인증서만 신뢰하는 {@link SSLContext}를 JVM 기본값으로 잠시 바꾼다. 운영 코드는 {@code
   * SSLSocketFactory.getDefault()}(= 기본 {@code SSLContext})를 그대로 쓰므로, factory를 주입하는 대신 기본값을 바꿔야 운영과
   * 같은 경로가 검증된다. 끝나면 반드시 되돌린다.
   */
  private static <T> T withTrustedTestTls(java.util.concurrent.Callable<T> body) throws Exception {
    SSLContext previous = SSLContext.getDefault();
    SSLContext.setDefault(tls.clientTrustContext());
    try {
      return body.call();
    } finally {
      SSLContext.setDefault(previous);
    }
  }

  private ScriptedSmtpServer plainServer(SmtpScript script) throws Exception {
    this.server = ScriptedSmtpServer.start(script);
    return this.server;
  }

  private static MailProvider.Message verification() {
    return new MailProvider.Message(AccountActionPurpose.EMAIL_VERIFICATION, RECIPIENT, TOKEN);
  }

  private static MimeMessage parse(String payload) throws Exception {
    return new MimeMessage(
        Session.getInstance(new Properties()),
        new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
  }

  private List<String> loggedMessages() {
    return this.logs.events().stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  // ---------- 접수 ----------

  @Test
  void 본문까지_접수되면_ACCEPTED이고_서버는_발신_수신_제목_링크를_받는다() throws Exception {
    ScriptedSmtpServer server = plainServer(SmtpScript.standard());
    SmtpMailProvider provider =
        newProvider(server, MailProviderProperties.Security.PLAINTEXT, true, "support@example.com");

    MailProvider.SendOutcome outcome = provider.send(verification());

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.ACCEPTED);
    assertThat(outcome.retryAt()).isEmpty();
    ScriptedSmtpServer.Transcript transcript = server.awaitFinished(0, Duration.ofSeconds(5));
    assertThat(transcript.verbs()).containsExactly("EHLO", "AUTH", "MAIL", "RCPT", "DATA", "QUIT");
    assertThat(transcript.authUsername()).isEqualTo(USERNAME);
    assertThat(transcript.authPassword()).isEqualTo(PASSWORD);
    assertThat(transcript.commandsOf("MAIL").get(0).line()).isEqualTo("MAIL FROM:<" + SENDER + ">");
    assertThat(transcript.commandsOf("RCPT").get(0).line())
        .isEqualTo("RCPT TO:<" + RECIPIENT + ">");
    assertThat(transcript.messages()).hasSize(1);

    MimeMessage received = parse(transcript.messages().get(0));
    assertThat(received.getFrom()).hasSize(1);
    assertThat(received.getFrom()[0].toString())
        .isEqualTo("=?UTF-8?Q?=EC=9D=B4=EC=9D=8C=28I=3AEUM=29?= <" + SENDER + ">");
    InternetAddress from = (InternetAddress) received.getFrom()[0];
    assertThat(from.getPersonal()).isEqualTo("이음(I:EUM)");
    assertThat(from.getAddress()).isEqualTo(SENDER);
    assertThat(received.getRecipients(MimeMessage.RecipientType.TO)[0].toString())
        .isEqualTo(RECIPIENT);
    assertThat(received.getReplyTo()[0].toString()).isEqualTo("support@example.com");
    assertThat(received.getSubject()).contains("이메일 인증");
    assertThat(received.getSentDate().toInstant()).isEqualTo(NOW);
    assertThat(received.getContentType())
        .startsWith("text/plain")
        .containsIgnoringCase("charset=UTF-8");
    assertThat(received.getContent()).isInstanceOf(String.class);
    String body = (String) received.getContent();
    assertThat(body).contains(VERIFY_URL + "#token=" + TOKEN);
    assertThat(body).doesNotContain("?token=");
    assertThat(body).doesNotContain("/" + TOKEN);
    assertThat(received.getHeader("Message-ID")).isNotNull();
  }

  @Test
  void 비밀번호_재설정은_재설정_링크와_제목을_쓴다() throws Exception {
    ScriptedSmtpServer server = plainServer(SmtpScript.standard());
    SmtpMailProvider provider = plainProvider(server);

    MailProvider.SendOutcome outcome =
        provider.send(
            new MailProvider.Message(AccountActionPurpose.PASSWORD_RESET, RECIPIENT, TOKEN));

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.ACCEPTED);
    MimeMessage received = parse(server.awaitFinished(0, Duration.ofSeconds(5)).messages().get(0));
    assertThat(((InternetAddress) received.getFrom()[0]).getPersonal()).isEqualTo("이음(I:EUM)");
    assertThat(received.getSubject()).contains("비밀번호 재설정");
    assertThat((String) received.getContent()).contains(RESET_URL + "#token=" + TOKEN);
    // 회신 주소를 설정하지 않으면 Reply-To 헤더를 만들지 않는다 (임의 기본값 없음).
    assertThat(received.getHeader("Reply-To")).isNull();
  }

  @Test
  void 자격증명_없는_PLAINTEXT는_AUTH를_시도하지_않는다() throws Exception {
    ScriptedSmtpServer server = plainServer(SmtpScript.standard().withoutAuth());
    SmtpMailProvider provider =
        newProvider(server, MailProviderProperties.Security.PLAINTEXT, false, null);

    MailProvider.SendOutcome outcome = provider.send(verification());

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.ACCEPTED);
    assertThat(server.awaitFinished(0, Duration.ofSeconds(5)).verbs()).doesNotContain("AUTH");
  }

  @Test
  void 느리지만_제한_시간_안에_접수하면_ACCEPTED다() throws Exception {
    ScriptedSmtpServer server =
        plainServer(SmtpScript.standard().delayAt(Step.END_OF_DATA, Duration.ofMillis(300)));
    SmtpMailProvider provider = plainProvider(server);

    assertThat(provider.send(verification()).result()).isEqualTo(MailProvider.SendResult.ACCEPTED);
  }

  @Test
  void 접수_확인_뒤_QUIT_응답_없이_끊겨도_ACCEPTED다() throws Exception {
    ScriptedSmtpServer server = plainServer(SmtpScript.standard().dropAt(Step.QUIT));
    SmtpMailProvider provider = plainProvider(server);

    assertThat(provider.send(verification()).result()).isEqualTo(MailProvider.SendResult.ACCEPTED);
  }

  // ---------- 인증·설정 ----------

  @Test
  void 인증이_거부되면_AUTH_CONFIG_ERROR이고_봉투를_보내지_않는다() throws Exception {
    ScriptedSmtpServer server =
        plainServer(
            SmtpScript.standard().reply(Step.AUTH, "535 5.7.8 Authentication credentials invalid"));
    SmtpMailProvider provider = plainProvider(server);

    MailProvider.SendOutcome outcome = provider.send(verification());

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.AUTH_CONFIG_ERROR);
    assertThat(outcome.retryAt()).isEmpty();
    assertThat(server.awaitFinished(0, Duration.ofSeconds(5)).verbs())
        .doesNotContain("MAIL", "RCPT", "DATA");
  }

  @Test
  void 자격증명이_있는데_서버가_AUTH를_제공하지_않으면_AUTH_CONFIG_ERROR이고_봉투를_보내지_않는다() throws Exception {
    ScriptedSmtpServer server = plainServer(SmtpScript.standard().withoutAuth());
    SmtpMailProvider provider = plainProvider(server);

    MailProvider.SendOutcome outcome = provider.send(verification());

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.AUTH_CONFIG_ERROR);
    assertThat(server.awaitFinished(0, Duration.ofSeconds(5)).verbs())
        .doesNotContain("MAIL", "RCPT", "DATA");
  }

  @Test
  void 발신_주소가_거부되면_AUTH_CONFIG_ERROR다() throws Exception {
    ScriptedSmtpServer server =
        plainServer(SmtpScript.standard().reply(Step.MAIL, "553 5.1.8 Sender address rejected"));
    SmtpMailProvider provider = plainProvider(server);

    assertThat(provider.send(verification()).result())
        .isEqualTo(MailProvider.SendResult.AUTH_CONFIG_ERROR);
    assertThat(server.awaitFinished(0, Duration.ofSeconds(5)).verbs()).doesNotContain("DATA");
  }

  @Test
  void 수신자_단계의_인증_필요_530은_AUTH_CONFIG_ERROR다() throws Exception {
    ScriptedSmtpServer server =
        plainServer(SmtpScript.standard().reply(Step.RCPT, "530 5.7.0 Authentication required"));
    SmtpMailProvider provider = plainProvider(server);

    assertThat(provider.send(verification()).result())
        .isEqualTo(MailProvider.SendResult.AUTH_CONFIG_ERROR);
  }

  // ---------- 거부 ----------

  @Test
  void 수신자_임시_거부_450은_RETRYABLE이고_본문을_보내지_않는다() throws Exception {
    ScriptedSmtpServer server =
        plainServer(SmtpScript.standard().reply(Step.RCPT, "450 4.2.0 Mailbox busy, try later"));
    SmtpMailProvider provider = plainProvider(server);

    MailProvider.SendOutcome outcome = provider.send(verification());

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.RETRYABLE);
    assertThat(outcome.retryAt()).isEmpty();
    assertThat(server.awaitFinished(0, Duration.ofSeconds(5)).messages()).isEmpty();
  }

  @Test
  void 수신자_영구_거부_550은_REJECTED다() throws Exception {
    ScriptedSmtpServer server =
        plainServer(SmtpScript.standard().reply(Step.RCPT, "550 5.1.1 User unknown"));
    SmtpMailProvider provider = plainProvider(server);

    assertThat(provider.send(verification()).result()).isEqualTo(MailProvider.SendResult.REJECTED);
  }

  @Test
  void DATA_명령이_임시_거부되면_RETRYABLE이고_본문을_보내지_않는다() throws Exception {
    ScriptedSmtpServer server =
        plainServer(SmtpScript.standard().reply(Step.DATA, "451 4.3.0 Temporary failure"));
    SmtpMailProvider provider = plainProvider(server);

    assertThat(provider.send(verification()).result()).isEqualTo(MailProvider.SendResult.RETRYABLE);
    assertThat(server.awaitFinished(0, Duration.ofSeconds(5)).messages()).isEmpty();
  }

  @Test
  void 본문_전송_뒤_명시적_임시_거부_451은_RETRYABLE다() throws Exception {
    ScriptedSmtpServer server =
        plainServer(SmtpScript.standard().reply(Step.END_OF_DATA, "451 4.3.0 Queue full"));
    SmtpMailProvider provider = plainProvider(server);

    assertThat(provider.send(verification()).result()).isEqualTo(MailProvider.SendResult.RETRYABLE);
  }

  @Test
  void 본문_전송_뒤_명시적_영구_거부_554는_REJECTED다() throws Exception {
    ScriptedSmtpServer server =
        plainServer(SmtpScript.standard().reply(Step.END_OF_DATA, "554 5.7.1 Message rejected"));
    SmtpMailProvider provider = plainProvider(server);

    assertThat(provider.send(verification()).result()).isEqualTo(MailProvider.SendResult.REJECTED);
  }

  // ---------- 응답 유실·연결 실패 ----------

  @Test
  void 본문_전송_뒤_응답_없이_끊기면_UNCERTAIN이다() throws Exception {
    ScriptedSmtpServer server = plainServer(SmtpScript.standard().dropAt(Step.END_OF_DATA));
    SmtpMailProvider provider = plainProvider(server);

    MailProvider.SendOutcome outcome = provider.send(verification());

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.UNCERTAIN);
    assertThat(server.awaitFinished(0, Duration.ofSeconds(5)).messages()).hasSize(1);
  }

  @Test
  void 본문_전송_전에_끊기면_RETRYABLE이다() throws Exception {
    ScriptedSmtpServer server = plainServer(SmtpScript.standard().dropAt(Step.DATA));
    SmtpMailProvider provider = plainProvider(server);

    assertThat(provider.send(verification()).result()).isEqualTo(MailProvider.SendResult.RETRYABLE);
    assertThat(server.awaitFinished(0, Duration.ofSeconds(5)).messages()).isEmpty();
  }

  @Test
  void 인사_전에_끊기면_RETRYABLE이다() throws Exception {
    ScriptedSmtpServer server = plainServer(SmtpScript.standard().dropAt(Step.GREETING));
    SmtpMailProvider provider = plainProvider(server);

    assertThat(provider.send(verification()).result()).isEqualTo(MailProvider.SendResult.RETRYABLE);
  }

  @Test
  void 연결이_거부되면_RETRYABLE이다() throws Exception {
    ScriptedSmtpServer closed = plainServer(SmtpScript.standard());
    int port = closed.port();
    closed.close();
    this.server = null;
    this.provider =
        new SmtpMailProvider(
            properties(port, MailProviderProperties.Security.PLAINTEXT, true, null),
            links(),
            PROVIDER_TIMEOUT,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(this.provider.send(verification()).result())
        .isEqualTo(MailProvider.SendResult.RETRYABLE);
  }

  // ---------- 전체 제한 시간 ----------

  @Test
  void 인사가_오지_않으면_전체_제한_시간에_RETRYABLE로_끝나고_연결을_실제로_끊는다() throws Exception {
    ScriptedSmtpServer server = plainServer(SmtpScript.standard().hangAt(Step.GREETING));
    SmtpMailProvider provider = plainProvider(server);

    long started = System.nanoTime();
    MailProvider.SendOutcome outcome = provider.send(verification());
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.RETRYABLE);
    // 소켓 read timeout(5초)이 아니라 전체 제한(1초)이 끝냈다.
    assertThat(elapsed).isLessThan(SOCKET_TIMEOUT).isGreaterThanOrEqualTo(PROVIDER_TIMEOUT);
    ScriptedSmtpServer.Transcript transcript = server.awaitFinished(0, Duration.ofSeconds(5));
    assertThat(transcript.peerClosed()).isTrue();
  }

  @Test
  void 본문_전송_뒤_응답이_오지_않으면_전체_제한_시간에_UNCERTAIN으로_끝난다() throws Exception {
    ScriptedSmtpServer server = plainServer(SmtpScript.standard().hangAt(Step.END_OF_DATA));
    SmtpMailProvider provider = plainProvider(server);

    long started = System.nanoTime();
    MailProvider.SendOutcome outcome = provider.send(verification());
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.UNCERTAIN);
    assertThat(elapsed).isLessThan(SOCKET_TIMEOUT);
    ScriptedSmtpServer.Transcript transcript = server.awaitFinished(0, Duration.ofSeconds(5));
    assertThat(transcript.messages()).hasSize(1);
    assertThat(transcript.peerClosed()).isTrue();
  }

  @Test
  void 제한_시간을_반복해서_넘겨도_스레드와_소켓이_쌓이지_않는다() throws Exception {
    ScriptedSmtpServer server = plainServer(SmtpScript.standard().hangAt(Step.GREETING));
    SmtpMailProvider provider = plainProvider(server);
    int attempts = 5;

    assertThat(provider.send(verification()).result()).isEqualTo(MailProvider.SendResult.RETRYABLE);
    // 첫 호출이 감시 스레드를 만든 뒤의 수를 기준으로 잰다. 같은 JVM의 다른 adapter(캐시된 Spring context)가 가진 감시 스레드는 이 테스트의
    // 관심사가 아니다.
    int watchdogsAfterFirst = watchdogThreadCount();
    for (int i = 1; i < attempts; i++) {
      // 결과 반환과 실행 슬롯 반환은 별개다. 반복 시도마다 실제 정리를 확인한다.
      assertThat(provider.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(provider.send(verification()).result())
          .isEqualTo(MailProvider.SendResult.RETRYABLE);
    }

    for (int i = 0; i < attempts; i++) {
      assertThat(server.awaitFinished(i, Duration.ofSeconds(5)).peerClosed()).isTrue();
    }
    // 호출마다 스레드가 늘지 않는다 — adapter 하나가 가진 감시 스레드는 하나뿐이다.
    assertThat(watchdogThreadCount()).isEqualTo(watchdogsAfterFirst);
    assertThat(watchdogsAfterFirst).isGreaterThanOrEqualTo(1);
  }

  private static int watchdogThreadCount() {
    return (int)
        Thread.getAllStackTraces().keySet().stream()
            .map(Thread::getName)
            .filter(name -> name.startsWith("mail-smtp-"))
            .count();
  }

  // ---------- TLS ----------

  @Test
  void STARTTLS를_광고하지_않으면_평문으로_인증도_발송도_하지_않고_AUTH_CONFIG_ERROR다() throws Exception {
    ScriptedSmtpServer server = plainServer(SmtpScript.standard());
    SmtpMailProvider provider =
        newProvider(server, MailProviderProperties.Security.STARTTLS, true, null);

    MailProvider.SendOutcome outcome = provider.send(verification());

    // STARTTLS는 필수 설정이다(#18 계약). 서버가 그것을 제공하지 않는 것은 설정 불일치이지 일시 장애가 아니다.
    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.AUTH_CONFIG_ERROR);
    assertThat(server.awaitFinished(0, Duration.ofSeconds(5)).verbs())
        .doesNotContain("AUTH", "MAIL", "RCPT", "DATA");
  }

  @Test
  void STARTTLS의_454_임시_거부는_RETRYABLE이며_평문으로_계속하지_않는다() throws Exception {
    ScriptedSmtpServer server =
        plainServer(
            SmtpScript.standard()
                .advertiseStartTls(tls.serverContext())
                .reply(Step.STARTTLS, "454 4.7.0 TLS temporarily unavailable"));
    SmtpMailProvider provider =
        newProvider(server, MailProviderProperties.Security.STARTTLS, true, null);

    MailProvider.SendOutcome outcome = provider.send(verification());

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.RETRYABLE);
    assertThat(outcome.retryAt()).isEmpty();
    ScriptedSmtpServer.Transcript transcript = server.awaitFinished(0, Duration.ofSeconds(5));
    assertThat(transcript.verbs())
        .contains("STARTTLS")
        .doesNotContain("AUTH", "MAIL", "RCPT", "DATA");
    assertThat(transcript.messages()).isEmpty();
    assertThat(transcript.tlsHandshakeFailed()).isFalse();
    assertThat(server.connectionCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void STARTTLS_응답_전_단절과_시간초과는_RETRYABLE이며_평문으로_계속하지_않는다(boolean timeout) throws Exception {
    SmtpScript script = SmtpScript.standard().advertiseStartTls(tls.serverContext());
    ScriptedSmtpServer server =
        plainServer(timeout ? script.hangAt(Step.STARTTLS) : script.dropAt(Step.STARTTLS));
    SmtpMailProvider provider =
        newProvider(server, MailProviderProperties.Security.STARTTLS, true, null);

    long started = System.nanoTime();
    MailProvider.SendOutcome outcome = provider.send(verification());
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.RETRYABLE);
    assertThat(outcome.retryAt()).isEmpty();
    assertThat(elapsed).isLessThan(SOCKET_TIMEOUT);
    ScriptedSmtpServer.Transcript transcript = server.awaitFinished(0, Duration.ofSeconds(5));
    assertThat(transcript.verbs())
        .contains("STARTTLS")
        .doesNotContain("AUTH", "MAIL", "RCPT", "DATA");
    assertThat(transcript.messages()).isEmpty();
    if (timeout) {
      assertThat(elapsed).isGreaterThanOrEqualTo(PROVIDER_TIMEOUT);
      assertThat(transcript.peerClosed()).isTrue();
    }
  }

  @Test
  void STARTTLS로_승격한_뒤에만_인증과_발송이_이뤄진다() throws Exception {
    ScriptedSmtpServer server =
        plainServer(SmtpScript.standard().advertiseStartTls(tls.serverContext()));
    SmtpMailProvider provider =
        newProvider(server, MailProviderProperties.Security.STARTTLS, true, null);

    MailProvider.SendOutcome outcome = withTrustedTestTls(() -> provider.send(verification()));

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.ACCEPTED);
    ScriptedSmtpServer.Transcript transcript = server.awaitFinished(0, Duration.ofSeconds(5));
    assertThat(transcript.verbs())
        .containsExactly("EHLO", "STARTTLS", "EHLO", "AUTH", "MAIL", "RCPT", "DATA", "QUIT");
    assertThat(
            transcript.commands().stream()
                .filter(c -> !c.overTls())
                .map(ScriptedSmtpServer.Command::line))
        .allMatch(line -> line.startsWith("EHLO") || line.startsWith("STARTTLS"));
    assertThat(transcript.commandsOf("AUTH")).allMatch(ScriptedSmtpServer.Command::overTls);
    assertThat(transcript.commandsOf("DATA")).allMatch(ScriptedSmtpServer.Command::overTls);
  }

  @Test
  void SSL_모드는_연결_시작부터_TLS다() throws Exception {
    this.server = ScriptedSmtpServer.startImplicitTls(SmtpScript.standard(), tls.serverContext());
    SmtpMailProvider provider =
        newProvider(this.server, MailProviderProperties.Security.SSL, true, null);

    MailProvider.SendOutcome outcome = withTrustedTestTls(() -> provider.send(verification()));

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.ACCEPTED);
    ScriptedSmtpServer.Transcript transcript = this.server.awaitFinished(0, Duration.ofSeconds(5));
    assertThat(transcript.tlsAtStart()).isTrue();
    assertThat(transcript.commands()).allMatch(ScriptedSmtpServer.Command::overTls);
  }

  @Test
  void 서버_인증서를_신뢰할_수_없으면_AUTH_CONFIG_ERROR이고_평문으로_계속하지_않는다() throws Exception {
    ScriptedSmtpServer server =
        plainServer(SmtpScript.standard().advertiseStartTls(tls.serverContext()));
    // 격리된 trust store를 주지 않는다 — 운영과 같은 JVM 기본 검증이 자체 서명 인증서를 거부해야 한다.
    SmtpMailProvider provider =
        newProvider(server, MailProviderProperties.Security.STARTTLS, true, null);

    MailProvider.SendOutcome outcome = provider.send(verification());

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.AUTH_CONFIG_ERROR);
    ScriptedSmtpServer.Transcript transcript = server.awaitFinished(0, Duration.ofSeconds(5));
    assertThat(transcript.tlsHandshakeFailed()).isTrue();
    assertThat(transcript.verbs()).doesNotContain("AUTH", "MAIL", "RCPT", "DATA");
  }

  // ---------- 로그 ----------

  @Test
  void 로그에는_수신_주소_토큰_자격증명_서버_원문이_없고_분류값만_남는다() throws Exception {
    ScriptedSmtpServer server =
        plainServer(
            SmtpScript.standard().reply(Step.RCPT, "550 5.1.1 <" + RECIPIENT + "> User unknown"));
    SmtpMailProvider provider = plainProvider(server);

    provider.send(verification());

    List<ILoggingEvent> events = this.logs.events();
    assertThat(events).isNotEmpty();
    assertThat(events).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    assertThat(loggedMessages())
        .allSatisfy(
            message ->
                assertThat(message)
                    .doesNotContain(RECIPIENT)
                    .doesNotContain(TOKEN)
                    .doesNotContain(PASSWORD)
                    .doesNotContain(USERNAME)
                    .doesNotContain("User unknown"));
    assertThat(events)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains("purpose=EMAIL_VERIFICATION")
                  .contains("result=REJECTED")
                  .contains("stage=ENVELOPE")
                  .contains("smtpCode=550");
            });
  }

  // ---------- 종료(close) ----------

  @Test
  void close는_진행_중인_호출의_연결을_끊어_제한_시간_전에_끝내고_새_호출을_거부한다() throws Exception {
    ScriptedSmtpServer server = plainServer(SmtpScript.standard().hangAt(Step.GREETING));
    // 제한 시간이 길다 — close가 감시만 멈추면 이 호출은 소켓 read timeout(5초)까지 남는다.
    this.provider =
        new SmtpMailProvider(
            properties(server.port(), MailProviderProperties.Security.PLAINTEXT, true, null),
            links(),
            Duration.ofSeconds(30),
            Clock.fixed(NOW, ZoneOffset.UTC));
    SmtpMailProvider provider = this.provider;
    CompletableFuture<MailProvider.SendOutcome> inFlight =
        CompletableFuture.supplyAsync(() -> provider.send(verification()));
    server.awaitConnection(0, Duration.ofSeconds(5));

    long begun = System.nanoTime();
    provider.close();
    Duration closeElapsed = Duration.ofNanos(System.nanoTime() - begun);

    MailProvider.SendOutcome outcome = inFlight.get(2, TimeUnit.SECONDS);
    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.RETRYABLE);
    assertThat(closeElapsed).isLessThan(Duration.ofSeconds(2));
    assertThat(server.awaitFinished(0, Duration.ofSeconds(5)).peerClosed()).isTrue();
    assertThat(watchdogThreadsOf(provider)).isEmpty();
    assertThatThrownBy(() -> provider.send(verification()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void close는_본문_전송_뒤_응답을_기다리던_호출을_UNCERTAIN으로_끝낸다() throws Exception {
    ScriptedSmtpServer server = plainServer(SmtpScript.standard().hangAt(Step.END_OF_DATA));
    this.provider =
        new SmtpMailProvider(
            properties(server.port(), MailProviderProperties.Security.PLAINTEXT, true, null),
            links(),
            Duration.ofSeconds(30),
            Clock.fixed(NOW, ZoneOffset.UTC));
    SmtpMailProvider provider = this.provider;
    CompletableFuture<MailProvider.SendOutcome> inFlight =
        CompletableFuture.supplyAsync(() -> provider.send(verification()));
    ScriptedSmtpServer.Transcript transcript = server.awaitConnection(0, Duration.ofSeconds(5));
    awaitTrue(() -> transcript.messages().size() == 1, "본문 수신");

    provider.close();

    assertThat(inFlight.get(2, TimeUnit.SECONDS).result())
        .isEqualTo(MailProvider.SendResult.UNCERTAIN);
    assertThat(server.awaitFinished(0, Duration.ofSeconds(5)).peerClosed()).isTrue();
  }

  @Test
  void SSL_모드에서_TLS_handshake가_멈춰도_전체_제한_시간에_끝나고_연결을_끊는다() throws Exception {
    // 평문 listener가 인사 없이 붙든다 → 클라이언트의 TLS handshake가 ServerHello를 기다리며 read에서 멈춘다.
    ScriptedSmtpServer server = plainServer(SmtpScript.standard().hangAt(Step.GREETING));
    SmtpMailProvider provider =
        newProvider(server, MailProviderProperties.Security.SSL, true, null);

    long started = System.nanoTime();
    MailProvider.SendOutcome outcome = provider.send(verification());
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

    assertThat(outcome.result()).isEqualTo(MailProvider.SendResult.RETRYABLE);
    assertThat(elapsed).isLessThan(SOCKET_TIMEOUT);
    assertThat(server.awaitFinished(0, Duration.ofSeconds(5)).peerClosed()).isTrue();
  }

  // ---------- Angus 프로토콜 trace ----------

  @Test
  void JUL과_logback_레벨을_모두_열어도_Angus_프로토콜_trace가_수신_주소와_토큰을_찍지_않는다() throws Exception {
    java.util.logging.Logger smtpJul =
        java.util.logging.Logger.getLogger("org.eclipse.angus.mail.smtp");
    java.util.logging.Logger protocolJul =
        java.util.logging.Logger.getLogger("org.eclipse.angus.mail.smtp.protocol");
    java.util.logging.Logger rootJul = java.util.logging.Logger.getLogger("");
    ch.qos.logback.classic.Logger smtpLogback =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.eclipse.angus.mail.smtp");
    java.util.logging.Level smtpBefore = smtpJul.getLevel();
    java.util.logging.Level protocolBefore = protocolJul.getLevel();
    java.util.logging.Level rootBefore = rootJul.getLevel();
    Level logbackBefore = smtpLogback.getLevel();
    boolean bridgeWasInstalled = SLF4JBridgeHandler.isInstalled();
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    smtpLogback.addAppender(captured);
    try {
      // 운영과 같은 연결(JUL → SLF4JBridgeHandler → logback)에서 운영자가 두 레벨을 모두 연 최악의 상황.
      if (!bridgeWasInstalled) {
        SLF4JBridgeHandler.install();
      }
      rootJul.setLevel(java.util.logging.Level.ALL);
      smtpJul.setLevel(java.util.logging.Level.ALL);
      protocolJul.setLevel(java.util.logging.Level.ALL);
      smtpLogback.setLevel(Level.TRACE);

      ScriptedSmtpServer server = plainServer(SmtpScript.standard());
      SmtpMailProvider provider = plainProvider(server);
      assertThat(provider.send(verification()).result())
          .isEqualTo(MailProvider.SendResult.ACCEPTED);
      // adapter가 만들어진 뒤 레벨을 다시 낮춰도 다음 호출에서 열리면 안 된다.
      smtpJul.setLevel(java.util.logging.Level.ALL);
      protocolJul.setLevel(java.util.logging.Level.ALL);
      assertThat(provider.send(verification()).result())
          .isEqualTo(MailProvider.SendResult.ACCEPTED);

      List<String> lines = captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
      assertThat(lines)
          .allSatisfy(
              line ->
                  assertThat(line)
                      .doesNotContain(RECIPIENT)
                      .doesNotContain(TOKEN)
                      .doesNotContain(PASSWORD)
                      .doesNotContain(USERNAME)
                      .doesNotContain("RCPT TO"));
    } finally {
      smtpLogback.detachAppender(captured);
      captured.stop();
      smtpLogback.setLevel(logbackBefore);
      smtpJul.setLevel(smtpBefore);
      protocolJul.setLevel(protocolBefore);
      rootJul.setLevel(rootBefore);
      if (!bridgeWasInstalled) {
        SLF4JBridgeHandler.uninstall();
      }
    }
  }

  private static List<String> watchdogThreadsOf(SmtpMailProvider provider) {
    return Thread.getAllStackTraces().keySet().stream()
        .map(Thread::getName)
        .filter(name -> name.equals(provider.watchdogThreadName()))
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
}
