package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.redis.RequiresRealRedis;
import com.itda.support.smtp.ScriptedSmtpServer;
import com.itda.support.smtp.SmtpScript;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.Email;
import com.itda.user.domain.MailDispatchStatus;
import com.itda.user.domain.MailOutboxEntry;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.AuthRedisKeys;
import com.itda.user.repository.MailOutboxJpaRepository;
import com.itda.user.repository.UserRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 접수 → 스케줄러 poll → 실제 Redis에 토큰 등록 → 대본형 SMTP 서버 접수 → {@code PROVIDER_ACCEPTED} 종결까지를 운영과 같은 조립으로
 * 잇는다.
 *
 * <p>여기서만 증명되는 것: 발송 실행이 켜진 context가 실제로 조립되고 스케줄러가 돌며, 메일에 실린 토큰이 운영 검증기로 검증되고 Redis의 현재 토큰과 일치한다는
 * 것. SMTP 서버는 루프백의 테스트 서버이고 외부로 나가는 연결은 없다.
 *
 * <p>Redis 키는 이 테스트가 만든 사용자의 것만 지운다. H2 행은 context 종료 시 create-drop이 정리한다.
 */
@SpringBootTest(
    properties = {
      // AFTER_CLASS의 create-drop이 다른 캐시 context의 공용 H2 테이블을 지우지 않게 격리한다.
      "spring.datasource.url=jdbc:h2:mem:mail_dispatch_smtp_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "itda.mail.dispatch.schedule.enabled=true",
      "itda.mail.dispatch.schedule.poll-interval=1s",
      "itda.mail.provider.security=PLAINTEXT",
      "itda.mail.provider.from-address=no-reply@example.com",
      "itda.mail.provider.from-name=이음",
      "itda.mail.link.email-verification-url=https://app.example.com/verify-email",
      "itda.mail.link.password-reset-url=https://app.example.com/reset-password"
    })
@RequiresRealRedis
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MailDispatchSmtpRedisTest {

  private static final String VERIFY_URL = "https://app.example.com/verify-email";
  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000);

  /** context가 만들어지기 전에 떠 있어야 하므로 정적으로 시작한다. 자격증명 없는 PLAINTEXT라 AUTH를 광고하지 않는다. */
  private static final ScriptedSmtpServer SMTP = startSmtp();

  @Autowired private UserRepository users;
  @Autowired private MailOutboxEnqueueService enqueueService;
  @Autowired private MailOutboxJpaRepository outboxRows;
  @Autowired private EmailVerificationTokenVerifier verifier;
  @Autowired private StringRedisTemplate redis;
  @Autowired private AuthRedisKeys keys;
  @Autowired private MailDispatchScheduler scheduler;

  private final List<String> createdKeys = new ArrayList<>();

  /**
   * 세 클래스의 격리 회귀 실행에서만 기본 class orderer로 지정한다. Gradle의 {@code --tests} 나열 순서는 실행 순서가 아니다. 같은
   * Jupiter 실행에 세 클래스가 모두 발견되어야 하며, 발송 context를 만든 뒤 일반 JUnit 테스트가 이어지도록 정렬한다.
   */
  public static final class SmtpFirstClassOrderer implements ClassOrderer {
    private static final List<String> ORDER =
        List.of(
            "MailDispatchSmtpRedisTest",
            "MailDispatchSchedulerTest",
            "MailDispatchConfigurationTest");

    @Override
    public void orderClasses(ClassOrdererContext context) {
      assertThat(context.getClassDescriptors().stream().map(d -> d.getTestClass().getSimpleName()))
          .containsExactlyInAnyOrderElementsOf(ORDER);
      context
          .getClassDescriptors()
          .sort(Comparator.comparingInt(d -> ORDER.indexOf(d.getTestClass().getSimpleName())));
      System.out.println(
          "Mail context cleanup regression: pid="
              + ProcessHandle.current().pid()
              + " order="
              + ORDER);
    }
  }

  private static ScriptedSmtpServer startSmtp() {
    try {
      return ScriptedSmtpServer.start(SmtpScript.standard().withoutAuth());
    } catch (IOException e) {
      throw new IllegalStateException("테스트 SMTP 서버를 시작하지 못했습니다.", e);
    }
  }

  @DynamicPropertySource
  static void smtpEndpoint(DynamicPropertyRegistry registry) {
    registry.add("itda.mail.provider.host", SMTP::host);
    registry.add("itda.mail.provider.port", SMTP::port);
  }

  @AfterAll
  static void stopSmtp(
      @Autowired MailDispatchScheduler scheduler, @Autowired SmtpMailProvider provider) {
    Set<String> threadNames =
        Set.of(
            "mail-dispatch-scheduler",
            provider.watchdogThreadName(),
            provider.watchdogThreadName().replace("watchdog", "execution"));
    List<Thread> threads =
        Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> threadNames.contains(thread.getName()))
            .toList();
    // @AfterAll은 Spring의 AFTER_CLASS context 폐기보다 먼저 실행된다. SMTP 서버를 먼저 닫아 진행 중 발송을
    // 끊지 않도록 생산자 → 제공자 → 서버 순으로 정리한다. 이후 @DirtiesContext가 DB를 포함한 context를 폐기한다.
    try {
      scheduler.stop();
    } finally {
      try {
        provider.close();
      } finally {
        SMTP.close();
      }
    }
    assertThat(scheduler.isRunning()).isFalse();
    assertThat(threads).isNotEmpty();
    assertThat(threads)
        .allSatisfy(thread -> assertThat(thread.isAlive()).as(thread.getName()).isFalse());
  }

  @AfterEach
  void deleteOnlyKeysCreatedHere() {
    if (!this.createdKeys.isEmpty()) {
      this.redis.delete(this.createdKeys);
    }
  }

  private static void await(BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(what + " 조건이 제한 시간 안에 충족되지 않았습니다.");
      }
      Thread.sleep(50);
    }
  }

  private User newUser(boolean emailVerified) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long sequence = SEQUENCE.incrementAndGet();
    User user =
        User.create(
            "mailer" + sequence,
            "argon2id$hash",
            UserRole.LEARNER,
            Email.of("mail-dispatch-" + sequence + "@example.test"),
            now);
    if (emailVerified) {
      user.verifyEmail(now);
    }
    return this.users.save(user);
  }

  private Optional<MailOutboxEntry> row(Long id) {
    return this.outboxRows.findById(id);
  }

  private static Optional<ScriptedSmtpServer.Transcript> transcriptFor(String email) {
    return SMTP.transcripts().stream()
        .filter(
            t ->
                t.commandsOf("RCPT").stream()
                    .anyMatch(c -> c.line().equals("RCPT TO:<" + email + ">")))
        .findFirst();
  }

  @Test
  void 접수된_작업을_스케줄러가_실제_SMTP로_보내고_PROVIDER_ACCEPTED로_종결하며_메일의_토큰은_Redis의_현재_토큰이다()
      throws Exception {
    assertThat(this.scheduler.isRunning()).isTrue();
    User user = newUser(false);
    String email = user.getEmail().getValue();
    this.createdKeys.add(
        this.keys.actionCurrent(user.getId(), AccountActionPurpose.EMAIL_VERIFICATION));

    MailOutboxEntry entry =
        this.enqueueService.enqueue(
            AccountActionPurpose.EMAIL_VERIFICATION, email, LocalDateTime.now(ZoneOffset.UTC));

    await(
        () ->
            row(entry.getId()).map(MailOutboxEntry::getStatus).orElse(null)
                == MailDispatchStatus.PROVIDER_ACCEPTED,
        "PROVIDER_ACCEPTED 종결");
    MailOutboxEntry completed = row(entry.getId()).orElseThrow();
    assertThat(completed.getAttemptCount()).isEqualTo(1);
    assertThat(completed.getLastErrorKind()).isNull();
    assertThat(completed.getCompletedAt()).isNotNull();
    // 종결 행은 수신 주소를 비운다 (ERD 3.5절).
    assertThat(completed.getRecipientEmail()).isNull();

    await(
        () -> transcriptFor(email).map(ScriptedSmtpServer.Transcript::isFinished).orElse(false),
        "SMTP 대화 종료");
    ScriptedSmtpServer.Transcript transcript = transcriptFor(email).orElseThrow();
    assertThat(transcript.verbs()).containsExactly("EHLO", "MAIL", "RCPT", "DATA", "QUIT");
    assertThat(transcript.messages()).hasSize(1);
    MimeMessage received =
        new MimeMessage(
            Session.getInstance(new Properties()),
            new ByteArrayInputStream(
                transcript.messages().get(0).getBytes(StandardCharsets.UTF_8)));
    String body = (String) received.getContent();
    String verificationLinkPrefix = VERIFY_URL + "#token=";
    int at = body.indexOf(verificationLinkPrefix);
    assertThat(at).isNotNegative();
    String token = body.substring(at + verificationLinkPrefix.length()).split("\\R", 2)[0];

    // 메일의 토큰은 운영 검증기가 받아들이고, Redis의 현재 토큰과 같은 발급이다.
    ActionTokenVerification verification = this.verifier.verify(token);
    assertThat(verification).isInstanceOf(ActionTokenVerification.Verified.class);
    ActionTokenVerification.Verified verified = (ActionTokenVerification.Verified) verification;
    assertThat(verified.userId()).isEqualTo(user.getId());
    this.createdKeys.add(
        this.keys.actionToken(
            user.getId(), AccountActionPurpose.EMAIL_VERIFICATION, verified.jti()));
    assertThat(
            this.redis
                .opsForValue()
                .get(
                    this.keys.actionCurrent(user.getId(), AccountActionPurpose.EMAIL_VERIFICATION)))
        .isEqualTo(verified.jti());
    assertThat(
            this.redis
                .opsForHash()
                .get(
                    this.keys.actionToken(
                        user.getId(), AccountActionPurpose.EMAIL_VERIFICATION, verified.jti()),
                    "status"))
        .isEqualTo("ACTIVE");
  }

  @Test
  void 이미_인증된_계정의_인증_메일은_SMTP에_연결하지_않고_SKIPPED로_종결한다() throws Exception {
    User user = newUser(true);
    String email = user.getEmail().getValue();
    int connectionsBefore = SMTP.connectionCount();

    MailOutboxEntry entry =
        this.enqueueService.enqueue(
            AccountActionPurpose.EMAIL_VERIFICATION, email, LocalDateTime.now(ZoneOffset.UTC));

    await(
        () ->
            row(entry.getId()).map(MailOutboxEntry::getStatus).orElse(null)
                == MailDispatchStatus.SKIPPED,
        "SKIPPED 종결");
    assertThat(transcriptFor(email)).isEmpty();
    // 다른 테스트의 연결이 섞일 수 있으므로 이 수신자의 대화가 없다는 것으로 판단한다. 연결 수는 참고값이다.
    assertThat(SMTP.connectionCount()).isGreaterThanOrEqualTo(connectionsBefore);
  }
}
