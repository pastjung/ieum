package com.itda.user.service;

import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * {@link MailDispatchWorker}를 주기적으로 실행한다.
 *
 * <p>스레드 하나짜리 전용 실행기에 poll과 정리를 fixed-delay로 예약한다. 같은 스레드가 둘을 순서대로 실행하므로 poll끼리도, poll과 정리도 겹치지 않고,
 * 대기열에는 예약된 두 작업 외에 아무것도 쌓이지 않는다. 다중 인스턴스 사이의 경쟁은 worker의 조건부 선점·가시성 만료가 맡는다 — 이 클래스는 한 프로세스 안의 실행만
 * 다룬다.
 *
 * <p>한 주기의 예외는 잡아서 다음 주기를 살린다. {@code scheduleWithFixedDelay}는 작업이 예외를 던지면 그 예약을 영구히 취소하는데, 그러면
 * 저장소가 잠깐 흔들린 뒤 메일이 영영 나가지 않는 상태가 기동 성공 뒤에 조용히 생긴다. 예외 객체·message는 로그에 남기지 않는다 — worker와 같은 규칙이다
 * (제공자·JDBC 예외에는 수신 주소·SQL이 들어갈 수 있다). 어느 작업이었는지만 남긴다.
 *
 * <h2>종료 순서</h2>
 *
 * <p>{@link SmartLifecycle#stop()}은 context 종료 시 bean 파괴보다 먼저 불린다.
 *
 * <ol>
 *   <li>새 주기를 예약하지 않는다({@code shutdown()}).
 *   <li>진행 중인 작업이 끝나기를 {@code shutdownWait}까지 기다린다. 끝나면 정상 종료다.
 *   <li>넘기면 interrupt({@code shutdownNow()})로 다음 후보 처리를 막고 제공자 연결 중단도 요청한다({@code abortInFlight}).
 *       SMTP 호출자 반환과 provider 내부 작업의 실제 종료는 별개이며 후자는 provider가 확인한다.
 *   <li>다시 {@code shutdownWait}만큼 실제 종료를 기다린다. 확인되면 WARN(중단했음), 확인되지 않으면 ERROR(스레드 잔존)로 <b>있는
 *       그대로</b> 남긴다. 종료를 확인하지 못한 것을 완료로 기록하지 않는다.
 * </ol>
 *
 * <p>중단된 작업의 outbox 행은 제공자 결과({@code RETRYABLE}·{@code UNCERTAIN})대로 재시도 대기가 되거나, 상태 갱신 전에 끊겼다면 가시성
 * 만료 뒤 다른 worker가 다시 선점한다(at-least-once). 종료되지 않은 실행기를 보존하고 재시작을 거부하므로 대체 스레드를 쌓지 않는다. 컨테이너의 정지 유예
 * 시간이 더 짧으면 그쪽이 먼저 끝낸다 — 이 값은 JVM 안의 상한일 뿐이다.
 */
public final class MailDispatchScheduler implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(MailDispatchScheduler.class);

  private final MailDispatchWorker worker;
  private final Duration pollInterval;
  private final Duration cleanupInterval;
  private final int batchSize;
  private final Duration shutdownWait;
  private final Runnable abortInFlight;

  private ScheduledThreadPoolExecutor executor;

  /**
   * @param abortInFlight 종료 대기를 넘긴 진행 중 작업의 외부 연결을 끊는 동작. {@code null}이면 끊을 수단이 없는 것으로 보고
   *     interrupt만 보낸다
   */
  public MailDispatchScheduler(
      MailDispatchWorker worker,
      Duration pollInterval,
      Duration cleanupInterval,
      int batchSize,
      Duration shutdownWait,
      Runnable abortInFlight) {
    if (worker == null) {
      throw new IllegalArgumentException("메일 발송 worker가 없습니다.");
    }
    requirePositive(pollInterval, "poll 주기");
    requirePositive(cleanupInterval, "정리 주기");
    if (batchSize < 1) {
      throw new IllegalArgumentException("batch 크기는 1 이상이어야 합니다.");
    }
    requirePositive(shutdownWait, "종료 대기");
    this.worker = worker;
    this.pollInterval = pollInterval;
    this.cleanupInterval = cleanupInterval;
    this.batchSize = batchSize;
    this.shutdownWait = shutdownWait;
    this.abortInFlight = abortInFlight;
  }

  /** 밀리초로 1 이상이어야 예약할 수 있다. 예약 값이 long으로 넘치면 여기서 거부한다. */
  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + "은(는) 0보다 커야 합니다.");
    }
    try {
      if (value.toMillis() < 1) {
        throw new IllegalArgumentException(name + "은(는) 1밀리초 이상이어야 합니다.");
      }
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(name + "이(가) 표현 범위를 넘습니다.");
    }
  }

  @Override
  public synchronized void start() {
    if (this.executor != null && !this.executor.isTerminated()) {
      if (this.executor.isShutdown()) {
        throw new IllegalStateException("이전 메일 작업의 실제 종료 전에는 다시 시작할 수 없습니다.");
      }
      return;
    }
    ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, "mail-dispatch-scheduler");
              thread.setDaemon(false);
              return thread;
            });
    // shutdown() 뒤에는 예약된 주기 작업을 더 실행하지 않는다.
    scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    scheduler.setRemoveOnCancelPolicy(true);
    // 첫 poll은 바로 실행한다 — 배포 직후 밀린 작업을 다음 주기까지 기다리게 할 이유가 없다. 보낼 작업이 있으면 기동 직후 실제 발송이 시작된다.
    scheduler.scheduleWithFixedDelay(
        () -> runSafely(Task.POLL), 0, this.pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    scheduler.scheduleWithFixedDelay(
        () -> runSafely(Task.CLEANUP),
        this.cleanupInterval.toMillis(),
        this.cleanupInterval.toMillis(),
        TimeUnit.MILLISECONDS);
    this.executor = scheduler;
    log.info(
        "메일 발송 스케줄러를 시작했습니다. pollInterval={} cleanupInterval={} batchSize={} shutdownWait={}",
        this.pollInterval,
        this.cleanupInterval,
        this.batchSize,
        this.shutdownWait);
  }

  @Override
  public synchronized void stop() {
    ScheduledThreadPoolExecutor scheduler = this.executor;
    if (scheduler == null) {
      return;
    }
    scheduler.shutdown();
    if (awaitTermination(scheduler)) {
      log.info("메일 발송 스케줄러를 정지했습니다. 진행 중이던 작업은 끝났습니다.");
      return;
    }
    // 다음 후보 선점을 먼저 막는다. SMTP 호출자는 interrupt로 반환하고, 소켓 중단은 아래에서 함께 요청한다.
    scheduler.shutdownNow();
    boolean connectionsAborted = this.abortInFlight != null;
    if (connectionsAborted) {
      this.abortInFlight.run();
    }
    if (awaitTermination(scheduler)) {
      log.warn(
          "메일 발송 작업이 종료 대기 안에 끝나지 않아 {} 종료를 확인했습니다. 중단된 작업은 제공자 결과대로 재시도되거나 가시성 만료 뒤 다시"
              + " 처리됩니다. shutdownWait={}",
          connectionsAborted ? "진행 중 연결을 끊고 interrupt를 보내" : "interrupt를 보내",
          this.shutdownWait);
      return;
    }
    log.error(
        "메일 발송 작업의 종료를 확인하지 못했습니다. 스레드가 아직 남아 있습니다. connectionsAborted={} shutdownWait={}",
        connectionsAborted,
        this.shutdownWait);
  }

  private boolean awaitTermination(ScheduledThreadPoolExecutor scheduler) {
    try {
      return scheduler.awaitTermination(this.shutdownWait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return this.executor != null && !this.executor.isShutdown();
  }

  private void runSafely(Task task) {
    try {
      switch (task) {
        case POLL -> this.worker.processOnce(this.batchSize);
        case CLEANUP -> this.worker.cleanUp();
      }
    } catch (RuntimeException e) {
      // 원인은 worker가 자기 로그에 안전한 필드로 남긴다. 여기서는 예외를 꺼내 쓰지 않는다.
      log.warn("메일 발송 작업 한 주기가 실패했습니다. 다음 주기에 다시 실행합니다. task={}", task);
    }
  }

  /** 로그 필드로 쓰는 닫힌 값. */
  private enum Task {
    POLL,
    CLEANUP
  }
}
