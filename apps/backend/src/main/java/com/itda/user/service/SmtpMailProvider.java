package com.itda.user.service;

import com.itda.user.domain.AccountActionPurpose;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import javax.net.SocketFactory;
import javax.net.ssl.SSLException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * SMTP 제공자 adapter (API 명세 3.8절 「제공자 호출 결과의 구분」, ERD 3.5절).
 *
 * <p>Jakarta Mail API + Angus Mail을 직접 쓴다. 발송 결과를 다섯 값으로 분류하려면 "어느 SMTP 단계에서 무엇을 확인했는가"가 필요한데, 그
 * 경계를 드러내는 것이 {@link SMTPTransport}·{@link SMTPSendFailedException}(명령·응답 코드)·{@link
 * SMTPAddressFailedException}(수신자 응답 코드)·{@link AuthenticationFailedException}·{@link
 * SMTPTransport#getLastReturnCode()}다. 예외 message나 클래스 이름의 문자열은 판정에 쓰지 않는다.
 *
 * <h2>단계와 분류</h2>
 *
 * <ul>
 *   <li>{@code CONNECT}: 연결·인사·EHLO·STARTTLS·AUTH. 인증 거부, TLS 협상 실패, 서버는 정상 응답했지만 우리 필수
 *       요건(STARTTLS)을 제공하지 않은 경우는 {@code AUTH_CONFIG_ERROR}. 그 밖의 연결 실패는 {@code RETRYABLE}(아무것도
 *       전송하지 않았다).
 *   <li>{@code ENVELOPE}: MAIL FROM·RCPT TO·DATA 명령. 4xx는 {@code RETRYABLE}(명시적 일시 거부), 5xx는 인증
 *       코드·발신 주소면 {@code AUTH_CONFIG_ERROR}, 아니면 {@code REJECTED}. 응답 없이 끊기면 본문을 보내기 전이므로 {@code
 *       RETRYABLE}.
 *   <li>{@code DATA}: 서버가 354로 본문을 받기 시작한 뒤. 본문 끝({@code .})에 4xx는 {@code RETRYABLE}, 5xx는 {@code
 *       REJECTED}, 응답이 유실되면 {@code UNCERTAIN} — 서버가 이미 접수했을 수 있다.
 *   <li>{@code SENT}: 본문 끝에 250을 받았다. {@code ACCEPTED}. 이후 QUIT·종료 오류는 결과를 바꾸지 않는다.
 * </ul>
 *
 * <p>단계 표시는 라이브러리 경계에서 얻는다. {@link SMTPTransport#sendMessage}는 DATA 명령이 354를 받은 뒤에야 {@link
 * MimeMessage#writeTo}를 부르므로, 그 호출을 가로채 {@code DATA} 단계로 표시한다.
 *
 * <h2>전체 제한 시간과 중단</h2>
 *
 * <p>Angus는 connect·read·write 제한만 제공하고 호출 전체를 제한하지 않으며, {@code SMTPTransport}의 메서드가 모두 {@code
 * synchronized}라 다른 스레드에서 {@code close()}로 끊을 수도 없다. 그래서 {@code mail.smtp.socketFactory}에 원시 소켓을
 * 기록하는 factory를 넣고(SSL·STARTTLS·PLAINTEXT 모두 이 factory가 원시 소켓을 만든다 — Angus는 그 위에 JVM 기본 {@code
 * SSLSocketFactory}로 TLS를 얹는다), 감시 스레드가 {@link MailDispatchProperties#providerTimeout()} 기한에, 그리고
 * {@link #abortInFlight()}·{@link #close()}가 즉시 그 소켓을 닫는다. 닫힌 소켓에서 blocking 중이던
 * connect·read·write·TLS handshake는 즉시 예외로 끝나고, 호출 스레드는 그 예외를 위 단계 규칙으로 분류한다.
 *
 * <p>SMTP 작업은 인스턴스당 전용 스레드 하나의 단일 슬롯에서 실행한다. 점유 중인 추가 호출은 대기열 없이 RETRYABLE이다. 호출자는 DNS(서버 주소·EHLO
 * 로컬 이름 조회 포함)와 무관하게 남은 전체 시간까지만 기다린다. 취소는 결과를 확정할 뿐 슬롯을 풀지 않는다. 슬롯은 소켓·transport 정리를 포함한 작업 본문이
 * 반환해야 풀린다.
 *
 * <p>JDK 동기 DNS의 실제 종료는 보장하지 않는다. 인터럽트를 무시하면 발송 스레드와 그 작업의 메모리가 남고 다음 발송은 거부된다. 대체 스레드를 만들지 않으며,
 * 해석기가 돌아오면 닫힌 소켓·단계 전환 검사로 늦은 발송을 막는다. 감시 스레드 하나는 Angus write timeout도 맡는다.
 *
 * <h2>종료</h2>
 *
 * <p>{@link #close()}는 새 호출을 거부하고, 진행 중인 소켓을 닫고, 감시 스레드와 실행 스레드의 실제 종료를 제한 시간 동안 확인한다. DNS 때문에 실행
 * 스레드가 남으면 종료 미확인으로 기록한다. 중단 결과는 본문 전송 전이면 {@code RETRYABLE}, 전송 뒤면 {@code UNCERTAIN}이다.
 *
 * <h2>로그</h2>
 *
 * <p>용도·결과·단계·사유·SMTP 응답 코드만 남긴다. 수신 주소·토큰·자격증명·서버 응답 원문·예외는 남기지 않는다. Angus의 디버그·프로토콜 로그(JUL {@code
 * org.eclipse.angus.mail.smtp}, {@code FINE} 이하)는 EHLO·RCPT TO·본문(토큰 포함)을 그대로 찍는다. 그 로거의 레벨을 {@code
 * INFO}로 고정해 막고({@link #pinProtocolTrace()}), 운영자가 뒤에 레벨을 낮춰도 매 호출 전에 다시 고정한다. 이것이 이 adapter가 보장하는
 * 범위이며, 다른 JUL 핸들러나 {@code Session.setDebug}를 통한 출력은 이 코드가 만들지 않는다.
 */
public final class SmtpMailProvider implements MailProvider, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SmtpMailProvider.class);

  /** RFC 4954: 인증이 필요하거나 실패했음을 뜻하는 응답 코드. 어느 명령에서 오든 설정 문제다. */
  private static final Set<Integer> AUTHENTICATION_CODES = Set.of(530, 534, 535, 538);

  private static final String CHARSET = "UTF-8";

  /** 감시 스레드 이름은 인스턴스마다 다르다 — 테스트와 스레드 덤프가 어느 adapter의 스레드인지 구분할 수 있게 한다. */
  private static final AtomicInteger INSTANCE_SEQUENCE = new AtomicInteger();

  /** 실제 작업 종료 대기 상한. DNS가 멈췄다면 호출자는 반환했어도 작업은 남을 수 있다. */
  private static final Duration CLOSE_DRAIN_WAIT = Duration.ofSeconds(5);

  /**
   * Angus 디버그·프로토콜 로그의 JUL 로거. JUL은 로거를 약하게 참조하므로 여기서 강하게 붙들어 레벨 고정이 GC로 사라지지 않게 한다.
   *
   * <p>{@code org.eclipse.angus.mail.smtp}는 {@code SMTPTransport}의 "DEBUG SMTP" 로거(EHLO·RCPT·응답 줄),
   * {@code .protocol}은 그 하위의 바이트 단위 trace 로거다.
   */
  private static final java.util.logging.Logger SMTP_DEBUG_LOGGER =
      java.util.logging.Logger.getLogger("org.eclipse.angus.mail.smtp");

  private static final java.util.logging.Logger SMTP_PROTOCOL_LOGGER =
      java.util.logging.Logger.getLogger("org.eclipse.angus.mail.smtp.protocol");

  private final MailProviderProperties properties;
  private final MailLinkProperties links;
  private final long providerTimeoutMillis;
  private final Clock clock;
  private final String watchdogThreadName;
  private final ScheduledThreadPoolExecutor watchdog;
  private final Object executionLock = new Object();
  private final Thread executionThread;
  // 대기열이 아니다. 준비/실행/정리 중인 작업 하나를 끝까지 소유하는 슬롯이다.
  private Job active;
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * TLS 신뢰는 JVM 기본 {@code SSLContext}와 Angus의 host 이름 검증({@code checkserveridentity})을 그대로 쓴다. 우회
   * 경로는 없다.
   */
  public SmtpMailProvider(
      MailProviderProperties properties,
      MailLinkProperties links,
      Duration providerTimeout,
      Clock clock) {
    if (properties == null || links == null || clock == null) {
      throw new IllegalArgumentException("SMTP 제공자 설정·링크 설정·시계가 모두 필요합니다.");
    }
    this.providerTimeoutMillis = requireSchedulableMillis(providerTimeout);
    this.properties = properties;
    this.links = links;
    this.clock = clock;
    this.watchdogThreadName = "mail-smtp-watchdog-" + INSTANCE_SEQUENCE.incrementAndGet();
    this.watchdog =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, this.watchdogThreadName);
              thread.setDaemon(true);
              return thread;
            });
    this.watchdog.setRemoveOnCancelPolicy(true);
    this.watchdog.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    this.executionThread =
        new Thread(this::executeLoop, this.watchdogThreadName.replace("watchdog", "execution"));
    this.executionThread.setDaemon(true);
    pinProtocolTrace();
    this.executionThread.start();
  }

  /** 밀리초로 1 이상이고 long으로 넘치지 않아야 감시를 예약할 수 있다. */
  private static long requireSchedulableMillis(Duration providerTimeout) {
    if (providerTimeout == null || providerTimeout.isZero() || providerTimeout.isNegative()) {
      throw new IllegalArgumentException("제공자 호출 제한 시간은 0보다 커야 합니다.");
    }
    long millis;
    try {
      millis = providerTimeout.toMillis();
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("제공자 호출 제한 시간이 표현 범위를 넘습니다.");
    }
    if (millis < 1) {
      throw new IllegalArgumentException("제공자 호출 제한 시간은 1밀리초 이상이어야 합니다.");
    }
    return millis;
  }

  @Override
  public SendOutcome send(Message message) {
    long started = System.nanoTime();
    pinProtocolTrace();
    Attempt attempt =
        new Attempt(started, TimeUnit.MILLISECONDS.toNanos(this.providerTimeoutMillis));
    boolean busy;
    synchronized (this.executionLock) {
      if (this.closed.get()) {
        throw new IllegalStateException("닫힌 SMTP 제공자로는 보낼 수 없습니다.");
      }
      busy = this.active != null;
      if (!busy) {
        this.active = new Job(message, attempt, MDC.get("requestId"));
        this.executionLock.notifyAll();
      }
    }
    if (busy) {
      // 로그 출력이 느려도 슬롯 소유권 lock을 붙잡지 않는다.
      logOutcome(
          message.purpose(), attempt, new Classification(SendResult.RETRYABLE, Reason.BUSY, null));
      return SendOutcome.of(SendResult.RETRYABLE);
    }
    Classification classification;
    try {
      classification =
          attempt.returned.get(Math.max(0, attempt.remainingNanos()), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      classification = cancel(attempt, AbortReason.TIMEOUT);
    } catch (InterruptedException e) {
      classification = cancel(attempt, AbortReason.SHUTDOWN);
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      // 작업의 예외 원문은 내보내지 않는다. 치명적 실패로 종료된 실행기를 재생성하지 않는다.
      classification = cancel(attempt, AbortReason.SHUTDOWN);
    }
    logOutcome(message.purpose(), attempt, classification);
    return SendOutcome.of(classification.result());
  }

  private Classification cancel(Attempt attempt, AbortReason reason) {
    Classification result = attempt.abort(reason);
    synchronized (this.executionLock) {
      if (this.active != null && this.active.attempt() == attempt) {
        this.executionThread.interrupt();
      }
    }
    return result;
  }

  /** 작업을 끝낸 뒤에만 슬롯을 반환한다. 결과 Future의 완료/취소와 실행 공간은 독립적이다. */
  private void executeLoop() {
    while (true) {
      Job job;
      synchronized (this.executionLock) {
        while (this.active == null && !this.closed.get()) {
          try {
            this.executionLock.wait();
          } catch (InterruptedException ignored) {
            // 아래의 닫힘 상태 또는 다음 작업을 다시 확인한다.
          }
        }
        if (this.active == null) {
          return;
        }
        job = this.active;
      }
      try {
        if (job.requestId() != null) {
          MDC.put("requestId", job.requestId());
        }
        execute(job.message(), job.attempt());
      } catch (Error fatal) {
        this.closed.set(true);
        job.attempt().abort(AbortReason.SHUTDOWN);
        this.watchdog.shutdownNow();
        // 원문을 uncaught exception handler로 흘리지 않는다.
        log.error("SMTP 실행 스레드에 치명적 오류가 발생했습니다. 새 발송을 받지 않습니다.");
      } finally {
        MDC.clear();
        synchronized (this.executionLock) {
          Thread.interrupted();
          this.active = null;
          job.attempt().returnResult();
          this.executionLock.notifyAll();
        }
      }
    }
  }

  private void execute(Message message, Attempt attempt) {
    ScheduledFuture<?> deadline = null;
    SMTPTransport transport = null;
    Classification classification = null;
    try {
      deadline =
          this.watchdog.schedule(
              () -> cancel(attempt, AbortReason.TIMEOUT),
              Math.max(0, attempt.remainingNanos()),
              TimeUnit.NANOSECONDS);
      attempt.advance(Stage.PREPARE);
      Session session = newSession(attempt);
      MimeMessage mime = compose(session, message, attempt);
      transport = (SMTPTransport) session.getTransport("smtp");
      attempt.advance(Stage.CONNECT);
      transport.connect(
          this.properties.host(),
          this.properties.port(),
          this.properties.hasCredentials() ? this.properties.username() : null,
          this.properties.hasCredentials() ? this.properties.password() : null);
      if (this.properties.hasCredentials() && !offersAuthentication(transport)) {
        // Angus는 서버가 AUTH를 광고하지 않으면 인증 없이 연결을 성공시킨다. 자격증명을 설정한 환경에서 인증 없이 보내는 것은 설정 불일치다.
        classification =
            new Classification(SendResult.AUTH_CONFIG_ERROR, Reason.AUTH_NOT_OFFERED, null);
      } else {
        attempt.advance(Stage.ENVELOPE);
        transport.sendMessage(mime, mime.getAllRecipients());
        classification =
            attempt.complete(new Classification(SendResult.ACCEPTED, Reason.ACCEPTED, null));
      }
    } catch (AuthenticationFailedException e) {
      classification =
          new Classification(SendResult.AUTH_CONFIG_ERROR, Reason.AUTHENTICATION_REJECTED, null);
    } catch (SMTPSendFailedException e) {
      classification = classifyCommandResponse(attempt, e.getCommand(), e.getReturnCode());
    } catch (SendFailedException e) {
      classification = classifyAddressFailure(attempt, e);
    } catch (MessagingException e) {
      classification =
          classifyByStage(attempt, isTlsFailure(e), isRequirementRefusedByServer(e, transport));
    } catch (RuntimeException e) {
      // Angus는 명령에 CR·LF가 섞이면 IllegalArgumentException을 던진다. 어느 단계였는지로만 분류한다.
      classification = classifyByStage(attempt, false, false);
    } finally {
      if (classification != null) {
        attempt.complete(classification);
      }
      // 접수가 확정된 뒤의 QUIT·종료 오류는 결과를 바꾸지 않는다. 감시는 종료까지 유지해 QUIT이 매달리는 것도 기한 안에 끊는다.
      closeQuietly(transport);
      if (deadline != null) {
        deadline.cancel(false);
      }
      attempt.closeSockets();
    }
  }

  /**
   * 진행 중인 모든 호출의 연결을 즉시 끊는다. 호출은 단계 규칙대로 {@code RETRYABLE}·{@code UNCERTAIN}으로 반환한다.
   *
   * <p>스케줄러가 종료 대기를 넘긴 작업을 끝내기 위해 부른다. 이 메서드 자체는 반환을 기다리지 않는다 — 기다리는 것은 호출자의 몫이다.
   */
  public void abortInFlight() {
    Attempt attempt;
    synchronized (this.executionLock) {
      attempt = this.active == null ? null : this.active.attempt();
    }
    if (attempt != null) {
      cancel(attempt, AbortReason.SHUTDOWN);
    }
  }

  /** 이 adapter의 감시 스레드 이름. 종료 뒤 스레드가 남지 않았는지 확인하는 데 쓴다. */
  public String watchdogThreadName() {
    return this.watchdogThreadName;
  }

  /**
   * 새 호출을 거부하고, 연결을 끊고, 감시·실행 스레드의 실제 종료를 제한 시간 동안 기다린다.
   *
   * <p>호출자에게 결과를 반환했더라도 실행 스레드가 남으면 경고로 남긴다. 완료로 기록하거나 대체 스레드를 만들지 않는다.
   */
  @Override
  public void close() {
    if (!this.closed.compareAndSet(false, true)) {
      return;
    }
    abortInFlight();
    synchronized (this.executionLock) {
      this.executionLock.notifyAll();
    }
    this.watchdog.shutdownNow();
    boolean watchdogStopped =
        awaitQuietly(() -> this.watchdog.awaitTermination(1, TimeUnit.SECONDS));
    boolean executionStopped =
        awaitQuietly(
            () -> {
              this.executionThread.join(CLOSE_DRAIN_WAIT.toMillis());
              return !this.executionThread.isAlive();
            });
    if (!watchdogStopped || !executionStopped) {
      log.warn(
          "SMTP 제공자를 닫았지만 종료를 확인하지 못한 부분이 있습니다. watchdogStopped={} executionStopped={}",
          watchdogStopped,
          executionStopped);
      return;
    }
    log.info("SMTP 제공자를 닫았습니다. 실행 스레드의 실제 종료를 확인했습니다.");
  }

  /** 테스트/진단용: 호출 결과 반환이 아니라 실제 작업 정리를 기다린다. */
  boolean awaitIdle(Duration timeout) throws InterruptedException {
    long started = System.nanoTime();
    synchronized (this.executionLock) {
      while (this.active != null) {
        long remaining = timeout.toNanos() - (System.nanoTime() - started);
        if (remaining <= 0) {
          return false;
        }
        TimeUnit.NANOSECONDS.timedWait(this.executionLock, remaining);
      }
      return true;
    }
  }

  private interface AwaitCall {
    boolean call() throws InterruptedException;
  }

  private static boolean awaitQuietly(AwaitCall call) {
    try {
      return call.call();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Angus 디버그·프로토콜 로거가 {@code FINE} 이하를 내보내지 않게 고정한다.
   *
   * <p>Boot는 JUL을 SLF4J로 잇는다. 그 경로에서 {@code logging.level}이나 JUL 설정으로 이 로거를 {@code FINE}·{@code
   * FINEST}로 열면 수신 주소·본문·토큰이 애플리케이션 로그에 그대로 실린다. 레벨을 올리는 것은 정보 손실이 아니다 — 이 로거의 {@code INFO} 이상은
   * SMTPUTF8 경고 하나뿐이고, 발송 결과는 이 adapter가 안전한 필드로 따로 남긴다.
   */
  static void pinProtocolTrace() {
    pin(SMTP_DEBUG_LOGGER);
    pin(SMTP_PROTOCOL_LOGGER);
  }

  private static void pin(java.util.logging.Logger logger) {
    if (logger.isLoggable(Level.FINE)) {
      logger.setLevel(Level.INFO);
    }
  }

  // ---------- 세션·메시지 ----------

  private Session newSession(Attempt attempt) {
    Properties props = new Properties();
    props.put("mail.smtp.host", this.properties.host());
    props.put("mail.smtp.port", Integer.toString(this.properties.port()));
    props.put("mail.smtp.auth", Boolean.toString(this.properties.hasCredentials()));
    props.put("mail.smtp.connectiontimeout", millis(this.properties.connectTimeout()));
    props.put("mail.smtp.timeout", millis(this.properties.readTimeout()));
    props.put("mail.smtp.writetimeout", millis(this.properties.writeTimeout()));
    // write timeout 감시를 소켓마다 새 실행기로 만들지 않고 이 adapter의 감시 스레드를 같이 쓴다.
    props.put("mail.smtp.executor.writetimeout", this.watchdog);
    // QUIT 응답을 기다리지 않는다. 결과는 이미 확정됐고, 응답 없는 서버에 read timeout만큼 더 매달릴 이유가 없다.
    props.put("mail.smtp.quitwait", "false");
    // 원시 소켓을 기록하는 factory. 세 방식 모두 여기서 만든 소켓 위에서 시작하므로 connect부터 감시가 끊을 수 있다.
    // 실패해도 기본 소켓으로 되돌아가지 않는다 — 되돌아가면 감시가 끊을 소켓을 잃는다.
    props.put("mail.smtp.socketFactory", attempt.socketFactory);
    props.put("mail.smtp.socketFactory.fallback", "false");
    // TLS는 JVM 기본 SSLSocketFactory가 얹는다. ssl.socketFactory를 따로 주지 않는 이유: 그 factory가 있으면 Angus가 원시
    // 소켓을
    // 스스로 만들어 connect 구간이 감시 밖으로 빠진다.
    props.put("mail.smtp.ssl.checkserveridentity", "true");
    switch (this.properties.security()) {
      case SSL -> props.put("mail.smtp.ssl.enable", "true");
      case STARTTLS -> {
        props.put("mail.smtp.starttls.enable", "true");
        // 서버가 STARTTLS를 광고하지 않으면 Angus가 연결을 실패시킨다. 평문으로 이어 가지 않는다.
        props.put("mail.smtp.starttls.required", "true");
      }
      case PLAINTEXT -> {
        // 격리된 테스트 환경 전용. 조립 단계에서 실행 환경·host를 검사한다.
      }
    }
    // Message-ID 생성이 로컬 host 이름을 DNS로 찾지 않게 발신 주소를 준다.
    props.put("mail.from", this.properties.fromAddress());
    // Session.setDebug 경로(System.out 출력)를 열지 않는다. JUL 경로는 pinProtocolTrace()가 막는다.
    props.put("mail.debug", "false");
    props.put("mail.debug.auth", "false");
    props.put("mail.debug.auth.username", "false");
    return Session.getInstance(props);
  }

  private MimeMessage compose(Session session, Message message, Attempt attempt)
      throws MessagingException {
    MimeMessage mime = new StageMarkingMessage(session, attempt);
    try {
      mime.setFrom(
          new InternetAddress(this.properties.fromAddress(), this.properties.fromName(), CHARSET));
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException("UTF-8을 지원하지 않는 JVM입니다.", e);
    }
    mime.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(message.recipientEmail()));
    if (this.properties.replyTo() != null) {
      mime.setReplyTo(new Address[] {new InternetAddress(this.properties.replyTo())});
    }
    mime.setSubject(subjectFor(message.purpose()), CHARSET);
    String link = this.links.linkFor(message.purpose(), message.actionToken());
    mime.setText(bodyFor(message.purpose(), link), CHARSET);
    mime.setSentDate(Date.from(this.clock.instant()));
    mime.saveChanges();
    return mime;
  }

  /** 제목·본문은 이 adapter가 정한다 (template 파일·별도 추상화 없음). 유효기간 문구는 API 명세 3.6.1절의 계약 값이다. */
  private static String subjectFor(AccountActionPurpose purpose) {
    return switch (purpose) {
      case EMAIL_VERIFICATION -> "[이음] 이메일 인증을 완료해 주세요";
      case PASSWORD_RESET -> "[이음] 비밀번호 재설정 안내";
    };
  }

  private static String bodyFor(AccountActionPurpose purpose, String link) {
    return switch (purpose) {
      case EMAIL_VERIFICATION ->
          """
          안녕하세요, 이음입니다.

          아래 링크를 열어 이메일 인증을 완료해 주세요. 이 링크는 24시간 동안 유효하며, 새 인증 메일을 요청하면 이전 링크는 더 이상 쓸 수 없습니다.

          """
              + link
              + """


          본인이 요청하지 않았다면 이 메일을 무시해 주세요.
          """;
      case PASSWORD_RESET ->
          """
          안녕하세요, 이음입니다.

          아래 링크를 열어 새 비밀번호를 설정해 주세요. 이 링크는 30분 동안 유효하며, 새 재설정 메일을 요청하면 이전 링크는 더 이상 쓸 수 없습니다.

          """
              + link
              + """


          본인이 요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 바뀌지 않습니다.
          """;
    };
  }

  private static boolean offersAuthentication(SMTPTransport transport) {
    return transport.supportsExtension("AUTH") || transport.supportsExtension("AUTH=LOGIN");
  }

  // ---------- 분류 ----------

  /** SMTP 명령에 서버가 응답 코드를 돌려준 경우. {@code command}는 Angus가 보낸 명령 원문이라 동사만 본다. */
  private static Classification classifyCommandResponse(Attempt attempt, String command, int code) {
    if (code >= 400 && code <= 499) {
      return new Classification(SendResult.RETRYABLE, Reason.TEMPORARY_REJECT, code);
    }
    if (code >= 500 && code <= 599) {
      if (AUTHENTICATION_CODES.contains(code)) {
        return new Classification(
            SendResult.AUTH_CONFIG_ERROR, Reason.AUTHENTICATION_REJECTED, code);
      }
      if ("MAIL".equals(verbOf(command))) {
        // 발신 주소·발신 권한은 우리 설정이다. 수신자는 아직 평가되지 않았다.
        return new Classification(SendResult.AUTH_CONFIG_ERROR, Reason.SENDER_REJECTED, code);
      }
      return new Classification(SendResult.REJECTED, Reason.PERMANENT_REJECT, code);
    }
    // EOF(-1)·읽기 실패(0)·예상 밖 코드: 응답을 판정에 쓸 수 없다. 어느 단계였는지로 분류한다.
    return classifyByStage(attempt, false, false);
  }

  /** RCPT TO 실패는 {@link SendFailedException}에 {@link SMTPAddressFailedException}이 연결돼 온다. */
  private static Classification classifyAddressFailure(
      Attempt attempt, SendFailedException failure) {
    Exception current = failure;
    for (int depth = 0; current != null && depth < 8; depth++) {
      if (current instanceof SMTPAddressFailedException addressFailure) {
        return classifyCommandResponse(attempt, "RCPT", addressFailure.getReturnCode());
      }
      current = current instanceof MessagingException me ? me.getNextException() : null;
    }
    return classifyByStage(attempt, false, false);
  }

  /**
   * 응답 코드 없이 끝난 경우. 판정 기준은 "어느 단계까지 확인했는가"다. 기한 초과·종료 중단이 원인이면 예외 종류와 무관하게 그렇게 표시한다.
   *
   * @param tlsFailure 원인 사슬에 {@link SSLException}이 있는지
   * @param requirementRefused 서버는 정상 응답했는데(마지막 응답 2xx, I/O 오류 없음) 우리 필수 요건이 충족되지 않아 Angus가 연결을
   *     거부했는지 — STARTTLS 필수인데 서버가 광고하지 않은 경우가 여기에 해당한다
   */
  private static Classification classifyByStage(
      Attempt attempt, boolean tlsFailure, boolean requirementRefused) {
    AbortReason aborted = attempt.abortReason();
    return switch (attempt.stage) {
      case PREPARE ->
          new Classification(
              aborted == null ? SendResult.REJECTED : SendResult.RETRYABLE,
              aborted == null ? Reason.COMPOSE_FAILED : aborted.reason,
              null);
      case CONNECT -> {
        if (aborted != null) {
          yield new Classification(SendResult.RETRYABLE, aborted.reason, null);
        }
        if (tlsFailure) {
          yield new Classification(SendResult.AUTH_CONFIG_ERROR, Reason.TLS_FAILED, null);
        }
        if (requirementRefused) {
          yield new Classification(SendResult.AUTH_CONFIG_ERROR, Reason.TLS_NOT_OFFERED, null);
        }
        yield new Classification(SendResult.RETRYABLE, Reason.CONNECT_FAILED, null);
      }
      case ENVELOPE ->
          new Classification(
              SendResult.RETRYABLE, aborted != null ? aborted.reason : Reason.RESPONSE_LOST, null);
      case DATA ->
          new Classification(
              SendResult.UNCERTAIN, aborted != null ? aborted.reason : Reason.RESPONSE_LOST, null);
      case SENT -> new Classification(SendResult.ACCEPTED, Reason.ACCEPTED, null);
    };
  }

  /** TLS 협상 실패는 원인 사슬의 {@link SSLException}으로 드러난다 (JSSE 타입, 문자열 판정이 아니다). */
  private static boolean isTlsFailure(MessagingException failure) {
    return causeChainHas(failure, SSLException.class);
  }

  /**
   * 서버와의 대화는 정상이었는데(I/O 오류 없음, 마지막 응답 2xx) Angus가 우리 필수 요건 때문에 연결을 거부한 경우. {@code
   * mail.smtp.starttls.required}가 만족되지 않으면 Angus는 I/O 없이 {@link MessagingException}을 던지며, 그때 {@link
   * SMTPTransport#getLastReturnCode()}는 EHLO의 250이다. 문자열이 아니라 응답 코드와 원인 타입으로 판정한다.
   */
  private static boolean isRequirementRefusedByServer(
      MessagingException failure, SMTPTransport transport) {
    if (transport == null || causeChainHas(failure, IOException.class)) {
      return false;
    }
    int last = transport.getLastReturnCode();
    return last >= 200 && last <= 299;
  }

  private static boolean causeChainHas(Throwable failure, Class<? extends Throwable> type) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 8; depth++) {
      if (type.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static String verbOf(String command) {
    if (command == null) {
      return "";
    }
    int end = 0;
    while (end < command.length() && command.charAt(end) != ' ' && command.charAt(end) != ':') {
      end++;
    }
    return command.substring(0, end).toUpperCase(Locale.ROOT);
  }

  private static void closeQuietly(SMTPTransport transport) {
    if (transport == null) {
      return;
    }
    try {
      transport.close();
    } catch (MessagingException | RuntimeException ignored) {
      // 소켓이 이미 닫혔거나 서버가 먼저 끊었다. 결과는 단계로 이미 확정됐다.
    }
  }

  private static String millis(Duration duration) {
    return Long.toString(duration.toMillis());
  }

  private static void logOutcome(
      AccountActionPurpose purpose, Attempt attempt, Classification classification) {
    if (classification.result() == SendResult.ACCEPTED) {
      log.debug("메일을 제공자에게 접수했습니다. purpose={} stage={}", purpose, attempt.stage);
      return;
    }
    log.warn(
        "메일 제공자 호출이 접수로 끝나지 않았습니다. purpose={} result={} stage={} reason={} smtpCode={}",
        purpose,
        classification.result(),
        attempt.stage,
        classification.reason(),
        classification.smtpCode() == null ? "-" : classification.smtpCode());
  }

  // ---------- 내부 타입 ----------

  /** 호출이 어디까지 확인했는지. 값은 닫힌 목록이라 로그 필드로 안정적이다. */
  enum Stage {
    PREPARE,
    CONNECT,
    ENVELOPE,
    DATA,
    SENT
  }

  /** 분류 사유. 로그 필드로만 쓰며 {@code mail_outbox}에는 기록하지 않는다. */
  enum Reason {
    ACCEPTED,
    COMPOSE_FAILED,
    CONNECT_FAILED,
    TLS_FAILED,
    TLS_NOT_OFFERED,
    AUTHENTICATION_REJECTED,
    AUTH_NOT_OFFERED,
    SENDER_REJECTED,
    TEMPORARY_REJECT,
    PERMANENT_REJECT,
    RESPONSE_LOST,
    BUSY,
    TIMEOUT,
    SHUTDOWN
  }

  /** 호출을 밖에서 끊은 이유. 결과 분류는 같고 로그 사유만 다르다. */
  enum AbortReason {
    TIMEOUT(Reason.TIMEOUT),
    SHUTDOWN(Reason.SHUTDOWN);

    private final Reason reason;

    AbortReason(Reason reason) {
      this.reason = reason;
    }
  }

  record Classification(SendResult result, Reason reason, Integer smtpCode) {}

  private record Job(Message message, Attempt attempt, String requestId) {}

  /**
   * 호출 한 번의 상태: 현재 단계, 중단 여부와 이유, 감시가 닫을 원시 소켓.
   *
   * <p>소켓 등록과 중단이 겹쳐도 소켓이 빠지지 않는다 — 중단 뒤에 등록된 소켓은 등록 시점에 바로 닫힌다.
   */
  static final class Attempt {
    private final List<Socket> sockets = new ArrayList<>();
    private final SocketFactory socketFactory = new RecordingSocketFactory(this);
    private volatile Stage stage = Stage.PREPARE;
    private volatile AbortReason abortReason;
    private final long started;
    private final long budgetNanos;
    private final CompletableFuture<Classification> returned = new CompletableFuture<>();
    private Classification decision;

    Attempt(long started, long budgetNanos) {
      this.started = started;
      this.budgetNanos = budgetNanos;
    }

    long remainingNanos() {
      return this.budgetNanos - (System.nanoTime() - this.started);
    }

    synchronized void advance(Stage next) throws MessagingException {
      requireActive();
      this.stage = next;
    }

    synchronized void requireActive() throws MessagingException {
      if (this.abortReason == null && remainingNanos() <= 0) {
        abortDecision(AbortReason.TIMEOUT);
      }
      if (this.abortReason != null) {
        throw new MessagingException("중단된 SMTP 호출입니다.");
      }
    }

    synchronized Classification complete(Classification value) {
      if (this.decision == null) {
        if (remainingNanos() <= 0) {
          abortDecision(AbortReason.TIMEOUT);
        } else {
          this.decision = value;
          if (value.result() == SendResult.ACCEPTED) {
            this.stage = Stage.SENT;
          }
        }
      }
      return this.decision;
    }

    synchronized void returnResult() {
      if (this.decision == null) {
        abortDecision(AbortReason.SHUTDOWN);
      }
      this.returned.complete(this.decision);
    }

    private void abortDecision(AbortReason reason) {
      if (this.abortReason == null) {
        this.abortReason = reason;
      }
      if (this.decision == null) {
        this.decision = classifyByStage(this, false, false);
      }
    }

    void register(Socket socket) {
      boolean closeNow;
      synchronized (this) {
        this.sockets.add(socket);
        closeNow = this.abortReason != null;
      }
      if (closeNow) {
        closeQuietly(socket);
      }
    }

    Classification abort(AbortReason reason) {
      Classification result;
      synchronized (this) {
        abortDecision(reason);
        result = this.decision;
      }
      // 호출자 반환을 본 다른 작업이 DNS를 해제해도, 등록된 소켓은 이미 닫혀 있어야 한다.
      closeSockets();
      this.returned.complete(result);
      return result;
    }

    AbortReason abortReason() {
      return this.abortReason;
    }

    void closeSockets() {
      List<Socket> snapshot;
      synchronized (this) {
        snapshot = List.copyOf(this.sockets);
      }
      for (Socket socket : snapshot) {
        closeQuietly(socket);
      }
    }

    private static void closeQuietly(Socket socket) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // 이미 닫혔다
      }
    }
  }

  /** DATA 명령이 354를 받은 뒤에만 불리는 {@link #writeTo}를 가로채 단계를 표시한다. */
  private static final class StageMarkingMessage extends MimeMessage {
    private final Attempt attempt;

    StageMarkingMessage(Session session, Attempt attempt) {
      super(session);
      this.attempt = attempt;
    }

    @Override
    public void writeTo(java.io.OutputStream out, String[] ignoreList)
        throws IOException, MessagingException {
      this.attempt.advance(Stage.DATA);
      super.writeTo(out, ignoreList);
    }
  }

  /**
   * 원시 소켓을 만들며 기록한다. Angus는 {@link #createSocket()} 뒤 직접 connect하고, SSL·STARTTLS는 그 소켓 위에 JVM 기본
   * {@code SSLSocketFactory}로 TLS를 얹는다 — 이 factory는 {@code SSLSocketFactory}가 아니어야 그 경로가 유지된다.
   */
  private static final class RecordingSocketFactory extends SocketFactory {
    private final Attempt attempt;

    RecordingSocketFactory(Attempt attempt) {
      this.attempt = attempt;
    }

    @Override
    public Socket createSocket() {
      Socket socket =
          new Socket() {
            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
              // SocketFetcher가 InetSocketAddress에서 DNS를 마친 뒤 진입하는 경계다.
              // 소켓 등록 이후 DNS 중에 취소됐거나 예산을 소진했다면 실제 connect를 시작하지 않는다.
              try {
                RecordingSocketFactory.this.attempt.requireActive();
              } catch (MessagingException stopped) {
                throw new SocketException("중단된 SMTP 연결입니다.");
              }
              super.connect(endpoint, timeout);
            }
          };
      this.attempt.register(socket);
      return socket;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
      return connect(createSocket(), new InetSocketAddress(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
        throws IOException {
      Socket socket = createSocket();
      socket.bind(new InetSocketAddress(localHost, localPort));
      return connect(socket, new InetSocketAddress(host, port));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
      return connect(createSocket(), new InetSocketAddress(host, port));
    }

    @Override
    public Socket createSocket(
        InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
      Socket socket = createSocket();
      socket.bind(new InetSocketAddress(localAddress, localPort));
      return connect(socket, new InetSocketAddress(address, port));
    }

    private static Socket connect(Socket socket, InetSocketAddress endpoint) throws IOException {
      socket.connect(endpoint);
      return socket;
    }
  }
}
