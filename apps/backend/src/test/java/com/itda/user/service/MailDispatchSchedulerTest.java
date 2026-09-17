package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.itda.support.log.CapturingAppender;
import com.itda.support.smtp.ScriptedSmtpServer;
import com.itda.support.smtp.SmtpScript;
import com.itda.user.domain.AccountActionPurpose;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 스케줄러를 진짜 실행기로 검증한다 — 주기 실행, 실패 뒤 계속 실행, 겹침 없음, 종료 순서.
 *
 * <p>worker는 호출 횟수만 세는 대역이다. worker의 판정은 {@link MailDispatchWorkerTest}가 맡는다.
 */
class MailDispatchSchedulerTest {

  private static final Duration SHORT = Duration.ofMillis(50);
  private static final Duration WAIT = Duration.ofSeconds(5);
  private static final String THREAD_PREFIX = "mail-dispatch-";

  private CountingWorker worker;
  private MailDispatchScheduler scheduler;
  private CapturingAppender logs;

  @BeforeEach
  void setUp() {
    this.worker = new CountingWorker();
    this.logs = CapturingAppender.attachTo(MailDispatchScheduler.class);
  }

  @AfterEach
  void tearDown() {
    this.logs.detach();
    if (this.scheduler != null && this.scheduler.isRunning()) {
      this.scheduler.stop();
    }
  }

  private MailDispatchScheduler newScheduler(
      Duration pollInterval, Duration cleanupInterval, int batchSize, Duration shutdownWait) {
    this.scheduler =
        new MailDispatchScheduler(
            this.worker, pollInterval, cleanupInterval, batchSize, shutdownWait, null);
    return this.scheduler;
  }

  private static void await(BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(what + " 조건이 제한 시간 안에 충족되지 않았습니다.");
      }
      Thread.sleep(10);
    }
  }

  private static List<String> dispatchThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .map(Thread::getName)
        .filter(name -> name.startsWith(THREAD_PREFIX))
        .toList();
  }

  @Test
  void 시작하면_poll과_정리를_주기적으로_실행하고_batch_크기를_전달한다() throws Exception {
    MailDispatchScheduler scheduler =
        newScheduler(SHORT, Duration.ofMillis(80), 7, Duration.ofSeconds(2));

    scheduler.start();

    assertThat(scheduler.isRunning()).isTrue();
    await(() -> this.worker.polls.get() >= 3, "poll 3회");
    await(() -> this.worker.cleanUps.get() >= 2, "정리 2회");
    assertThat(this.worker.batchSizes).allMatch(size -> size == 7);
    assertThat(this.worker.threadNames).allMatch(name -> name.startsWith(THREAD_PREFIX));
  }

  @Test
  void 한_주기의_실패는_다음_주기를_막지_않고_예외_내용을_로그에_남기지_않는다() throws Exception {
    this.worker.failPollsUntil = 2;
    this.worker.failCleanUpsUntil = 1;
    MailDispatchScheduler scheduler =
        newScheduler(SHORT, Duration.ofMillis(60), 5, Duration.ofSeconds(2));

    scheduler.start();

    await(() -> this.worker.polls.get() >= 4, "실패 뒤 poll 계속");
    await(() -> this.worker.cleanUps.get() >= 2, "실패 뒤 정리 계속");
    List<ILoggingEvent> warnings =
        this.logs.events().stream().filter(e -> e.getLevel() == Level.WARN).toList();
    assertThat(warnings).hasSizeGreaterThanOrEqualTo(3);
    assertThat(warnings).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
    assertThat(warnings.stream().map(ILoggingEvent::getFormattedMessage))
        .anyMatch(m -> m.contains("task=POLL"))
        .anyMatch(m -> m.contains("task=CLEANUP"))
        .allMatch(m -> !m.contains(CountingWorker.SECRET));
  }

  @Test
  void poll과_정리는_겹치지_않고_한_스레드에서_실행된다() throws Exception {
    this.worker.workDuration = Duration.ofMillis(60);
    MailDispatchScheduler scheduler = newScheduler(SHORT, SHORT, 5, Duration.ofSeconds(2));

    scheduler.start();

    await(() -> this.worker.polls.get() >= 3 && this.worker.cleanUps.get() >= 3, "각 3회");
    assertThat(this.worker.maxConcurrent.get()).isEqualTo(1);
    assertThat(this.worker.threadNames.stream().distinct()).hasSize(1);
  }

  @Test
  void 정지하면_새_주기를_예약하지_않고_진행_중인_작업을_끝까지_기다린_뒤_스레드를_정리한다() throws Exception {
    this.worker.workDuration = Duration.ofMillis(300);
    MailDispatchScheduler scheduler =
        newScheduler(SHORT, Duration.ofMinutes(1), 5, Duration.ofSeconds(2));
    scheduler.start();
    await(() -> this.worker.started.get() >= 1, "첫 poll 시작");

    scheduler.stop();

    assertThat(scheduler.isRunning()).isFalse();
    // 진행 중이던 작업은 끊기지 않고 끝났다.
    assertThat(this.worker.completed.get()).isEqualTo(this.worker.started.get());
    assertThat(this.worker.interrupted.get()).isZero();
    int afterStop = this.worker.started.get();
    Thread.sleep(200);
    assertThat(this.worker.started.get()).isEqualTo(afterStop);
    await(() -> dispatchThreads().isEmpty(), "스케줄 스레드 종료");
    assertThat(this.logs.events().stream().map(ILoggingEvent::getLevel))
        .doesNotContain(Level.WARN, Level.ERROR);
  }

  @Test
  void 종료_대기를_넘기면_경고를_남기고_강제_종료한다() throws Exception {
    this.worker.workDuration = Duration.ofSeconds(3);
    MailDispatchScheduler scheduler =
        newScheduler(SHORT, Duration.ofMinutes(1), 5, Duration.ofMillis(200));
    scheduler.start();
    await(() -> this.worker.started.get() >= 1, "첫 poll 시작");

    long begun = System.nanoTime();
    scheduler.stop();
    Duration elapsed = Duration.ofNanos(System.nanoTime() - begun);

    assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
    assertThat(scheduler.isRunning()).isFalse();
    await(() -> this.worker.interrupted.get() >= 1, "진행 중 작업 interrupt");
    await(() -> dispatchThreads().isEmpty(), "스케줄 스레드 종료");
    assertThat(this.logs.events())
        .anySatisfy(
            e -> {
              assertThat(e.getLevel()).isEqualTo(Level.WARN);
              assertThat(e.getFormattedMessage()).contains("종료 대기");
              assertThat(e.getThrowableProxy()).isNull();
            });
  }

  @Test
  void 시작하지_않은_스케줄러의_정지는_아무_일도_하지_않고_두_번_시작해도_한_벌만_돈다() throws Exception {
    MailDispatchScheduler scheduler =
        newScheduler(SHORT, Duration.ofMinutes(1), 5, Duration.ofSeconds(2));

    scheduler.stop();
    assertThat(scheduler.isRunning()).isFalse();

    scheduler.start();
    scheduler.start();
    await(() -> this.worker.polls.get() >= 2, "poll 2회");
    assertThat(dispatchThreads()).hasSize(1);
  }

  @Test
  void 주기_batch_종료_대기가_양수가_아니면_거부한다() {
    assertThatThrownBy(
            () ->
                new MailDispatchScheduler(
                    this.worker,
                    Duration.ZERO,
                    Duration.ofMinutes(1),
                    5,
                    Duration.ofSeconds(1),
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MailDispatchScheduler(
                    this.worker, SHORT, Duration.ofSeconds(-1), 5, Duration.ofSeconds(1), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MailDispatchScheduler(
                    this.worker, SHORT, Duration.ofMinutes(1), 0, Duration.ofSeconds(1), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MailDispatchScheduler(this.worker, SHORT, Duration.ofMinutes(1), 5, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MailDispatchScheduler(
                    null, SHORT, Duration.ofMinutes(1), 5, Duration.ofSeconds(1), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- 실제 SMTP 호출이 진행 중일 때의 종료 ----------

  /**
   * 소켓 I/O에 막힌 worker 스레드는 {@code shutdownNow()}의 interrupt로 깨어나지 않는다. 종료가 "확인"으로 기록되려면 연결이 실제로 끊기고
   * 스레드가 실제로 사라져야 한다.
   */
  @Test
  void 진행_중인_SMTP_호출이_종료_대기를_넘기면_연결을_끊어_스레드를_실제로_끝내고_그_사실을_기록한다() throws Exception {
    try (ScriptedSmtpServer server =
        ScriptedSmtpServer.start(SmtpScript.standard().hangAt(SmtpScript.Step.GREETING))) {
      SmtpMailProvider provider =
          new SmtpMailProvider(
              new MailProviderProperties(
                  "127.0.0.1",
                  server.port(),
                  MailProviderProperties.Security.PLAINTEXT,
                  null,
                  null,
                  "no-reply@example.com",
                  null,
                  null,
                  Duration.ofSeconds(10),
                  Duration.ofSeconds(10),
                  Duration.ofSeconds(10)),
              new MailLinkProperties(
                  "https://app.example.com/verify-email", "https://app.example.com/reset-password"),
              Duration.ofSeconds(30),
              Clock.systemUTC());
      this.worker.sendVia = provider;
      MailDispatchScheduler scheduler =
          new MailDispatchScheduler(
              this.worker,
              SHORT,
              Duration.ofMinutes(1),
              5,
              Duration.ofMillis(300),
              provider::abortInFlight);
      this.scheduler = scheduler;
      scheduler.start();
      server.awaitConnection(0, Duration.ofSeconds(5));

      long begun = System.nanoTime();
      scheduler.stop();
      Duration elapsed = Duration.ofNanos(System.nanoTime() - begun);

      assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
      assertThat(server.awaitFinished(0, Duration.ofSeconds(5)).peerClosed()).isTrue();
      assertThat(this.worker.completed.get()).isEqualTo(this.worker.started.get());
      assertThat(this.worker.lastOutcome).isEqualTo(MailProvider.SendResult.RETRYABLE);
      assertThat(dispatchThreads()).isEmpty();
      assertThat(this.logs.events())
          .anySatisfy(
              e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).contains("연결을 끊").contains("종료를 확인");
              });
      assertThat(this.logs.events().stream().map(ILoggingEvent::getLevel))
          .doesNotContain(Level.ERROR);
      provider.close();
    }
  }

  @Test
  void 연결을_끊을_수단이_없어_스레드가_남으면_완료로_기록하지_않고_ERROR로_남긴다() throws Exception {
    this.worker.ignoreInterrupt = true;
    // 종료 대기(200ms) + 중단 뒤 확인 대기(200ms)를 넘기되, 테스트 끝의 자연 종료 대기(5s) 안에는 끝난다.
    this.worker.workDuration = Duration.ofSeconds(3);
    MailDispatchScheduler scheduler =
        newScheduler(SHORT, Duration.ofMinutes(1), 5, Duration.ofMillis(200));
    scheduler.start();
    await(() -> this.worker.started.get() >= 1, "첫 poll 시작");

    scheduler.stop();

    assertThat(scheduler.isRunning()).isFalse();
    assertThat(dispatchThreads()).isNotEmpty();
    assertThatThrownBy(scheduler::start).isInstanceOf(IllegalStateException.class);
    assertThat(dispatchThreads()).hasSize(1);
    assertThat(this.logs.events())
        .anySatisfy(
            e -> {
              assertThat(e.getLevel()).isEqualTo(Level.ERROR);
              assertThat(e.getFormattedMessage()).contains("종료를 확인하지 못했습니다");
            });
    assertThat(this.logs.events().stream().map(ILoggingEvent::getFormattedMessage))
        .noneMatch(m -> m.contains("강제 종료했습니다"));
    await(() -> dispatchThreads().isEmpty(), "작업 자연 종료");
  }

  /** 호출만 세는 worker 대역. 저장소·제공자는 쓰지 않는다. */
  private static final class CountingWorker extends MailDispatchWorker {
    static final String SECRET = "test-recipient@example.test";

    final AtomicInteger polls = new AtomicInteger();
    final AtomicInteger cleanUps = new AtomicInteger();
    final AtomicInteger started = new AtomicInteger();
    final AtomicInteger completed = new AtomicInteger();
    final AtomicInteger interrupted = new AtomicInteger();
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxConcurrent = new AtomicInteger();
    final List<Integer> batchSizes = new CopyOnWriteArrayList<>();
    final List<String> threadNames = new CopyOnWriteArrayList<>();
    volatile int failPollsUntil;
    volatile int failCleanUpsUntil;
    volatile Duration workDuration = Duration.ZERO;

    /** 소켓 I/O처럼 interrupt에 반응하지 않는 작업을 흉내 낸다. */
    volatile boolean ignoreInterrupt;

    /** 지정하면 매 poll이 이 adapter로 실제 SMTP 호출을 한다. */
    volatile SmtpMailProvider sendVia;

    volatile MailProvider.SendResult lastOutcome;
    final CountDownLatch never = new CountDownLatch(1);

    CountingWorker() {
      super(null, null, null, null, null, null, null, null);
    }

    @Override
    public int processOnce(int batchSize) {
      this.batchSizes.add(batchSize);
      int count = this.polls.incrementAndGet();
      run(count <= this.failPollsUntil);
      return 0;
    }

    @Override
    public int cleanUp() {
      int count = this.cleanUps.incrementAndGet();
      run(count <= this.failCleanUpsUntil);
      return 0;
    }

    private void run(boolean fail) {
      this.threadNames.add(Thread.currentThread().getName());
      this.started.incrementAndGet();
      int concurrent = this.inFlight.incrementAndGet();
      this.maxConcurrent.accumulateAndGet(concurrent, Math::max);
      try {
        if (this.sendVia != null) {
          this.lastOutcome =
              this.sendVia
                  .send(
                      new MailProvider.Message(
                          AccountActionPurpose.EMAIL_VERIFICATION,
                          "learner-shutdown@example.com",
                          "header.payload.signature"))
                  .result();
        }
        if (!this.workDuration.isZero()) {
          if (this.ignoreInterrupt) {
            long until = System.nanoTime() + this.workDuration.toNanos();
            while (System.nanoTime() < until) {
              Thread.onSpinWait();
            }
          } else if (this.never.await(this.workDuration.toMillis(), TimeUnit.MILLISECONDS)) {
            return;
          }
        }
        if (fail) {
          throw new IllegalStateException("provider detail " + SECRET);
        }
        this.completed.incrementAndGet();
      } catch (InterruptedException e) {
        this.interrupted.incrementAndGet();
        Thread.currentThread().interrupt();
      } finally {
        this.inFlight.decrementAndGet();
      }
    }
  }
}
