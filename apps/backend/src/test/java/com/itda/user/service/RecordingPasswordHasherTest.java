package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RecordingPasswordHasher}의 동시 접근 안전성 회귀 테스트 (Issue #17 1단계).
 *
 * <p>가입 동시성 테스트가 이 fixture를 여러 스레드에서 공유한다. 기록·호출 횟수 조회·스냅샷 생성이 겹쳐도 예외·기록 누락·잘못된 중복이 없어야 한다.
 *
 * <p>동시 기록의 전체 순서는 고정하지 않고, 서로 다른 시점에 얻은 호출 횟수와 스냅샷 크기가 같다고 단언하지 않는다. 이 테스트의 통과는 모든 스케줄에 대한 증명이 아니라
 * 회귀 방지용 검증이다.
 */
class RecordingPasswordHasherTest {

  private RecordingPasswordHasher hasher;

  @BeforeEach
  void setUp() {
    this.hasher = new RecordingPasswordHasher();
  }

  @Test
  void hash_경로는_동시_기록_조회_스냅샷_생성에서_누락과_중복_없이_기록한다() throws Exception {
    new ConcurrentRecording(
            "hash", this.hasher::hash, this.hasher::hashCallCount, this.hasher::hashedPasswords)
        .run();
  }

  @Test
  void matches_경로는_동시_기록_조회_스냅샷_생성에서_누락과_중복_없이_기록한다() throws Exception {
    // 설정용 setter는 동시 작업을 제출하기 전에만 호출한다. 실행 중 설정 변경은 이 fixture의 계약이 아니다.
    this.hasher.willReturnMatches(true);
    new ConcurrentRecording(
            "matches",
            password -> assertThat(this.hasher.matches(password, "stored-hash")).isTrue(),
            this.hasher::matchesCallCount,
            this.hasher::verifiedPasswords)
        .run();
  }

  @Test
  void 스냅샷은_이후_기록에_영향받지_않고_수정할_수_없다() {
    this.hasher.hash("first");
    this.hasher.matches("m-first", "stored-hash");
    List<String> hashedSnapshot = this.hasher.hashedPasswords();
    List<String> verifiedSnapshot = this.hasher.verifiedPasswords();

    this.hasher.hash("second");
    this.hasher.matches("m-second", "stored-hash");

    assertThat(hashedSnapshot).containsExactly("first");
    assertThat(verifiedSnapshot).containsExactly("m-first");
    assertThat(this.hasher.hashedPasswords()).containsExactly("first", "second");
    assertThat(this.hasher.verifiedPasswords()).containsExactly("m-first", "m-second");
    assertThatThrownBy(() -> hashedSnapshot.add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> verifiedSnapshot.add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ---------- 시나리오 자체의 실패·취소·정리 경로 ----------

  @Test
  void 작업_실패는_원인_그대로_호출자에게_전달되고_실행기는_정리된다() {
    // 후반 구간의 값 하나에서만 합성 예외를 던진다. fixture 자체는 고장 내지 않는다.
    String poison = ConcurrentRecording.value("hash", 0, ConcurrentRecording.FIRST_HALF);
    Consumer<String> record =
        password -> {
          if (password.equals(poison)) {
            throw new IllegalStateException("합성 작업 실패");
          }
          this.hasher.hash(password);
        };

    assertThatThrownBy(
            () ->
                new ConcurrentRecording(
                        "hash", record, this.hasher::hashCallCount, this.hasher::hashedPasswords)
                    .run())
        .isInstanceOf(AssertionError.class)
        .hasCauseInstanceOf(IllegalStateException.class)
        .satisfies(
            error -> {
              assertThat(error.getCause()).hasMessage("합성 작업 실패");
              // 실패 뒤 남은 작업이 협력적으로 취소·종료됐으므로 실행기 미종료가 부수 실패로 남지 않는다.
              assertThat(error.getSuppressed())
                  .noneMatch(ConcurrentRecording::isExecutorNotTerminated);
            });
  }

  @Test
  void 작업이_멈추면_제한_시간_초과로_실패하고_reader는_실제_종료되며_실행기_미종료를_보존한다() throws Exception {
    // 인터럽트를 무시하고 release 전까지 멈추는 작업을 흉내 낸다. writersDone이 0이 되지 않으므로
    // reader는 count 조건으로는 루프를 벗어날 수 없고, 취소(인터럽트)나 자체 제한 시간으로만 종료할 수 있다.
    CountDownLatch release = new CountDownLatch(1);
    String stall = ConcurrentRecording.value("hash", 0, ConcurrentRecording.FIRST_HALF);
    Consumer<String> record =
        password -> {
          this.hasher.hash(password);
          if (password.equals(stall)) {
            while (release.getCount() > 0) {
              Thread.interrupted();
              LockSupport.parkNanos(1_000_000L);
            }
          }
        };
    ConcurrentRecording recording =
        new ConcurrentRecording(
            "hash",
            record,
            this.hasher::hashCallCount,
            this.hasher::hashedPasswords,
            Duration.ofSeconds(1));

    boolean terminatedAfterRelease;
    try {
      Throwable error = catchThrowable(recording::run);
      assertThat(error)
          .isInstanceOf(AssertionError.class)
          .hasCauseInstanceOf(TimeoutException.class);
      // writer를 아직 풀지 않은 상태: 실행기 미종료 사실이 원인을 덮지 않고 보존된다.
      assertThat(error.getSuppressed()).anyMatch(ConcurrentRecording::isExecutorNotTerminated);
      // Future의 취소 상태가 아니라 reader 작업 본문이 finally에서 남긴 종료 기록으로 확인한다. reader는 반복 관측 단계에
      // 도달한 뒤 취소(인터럽트) 또는 자체 제한 시간으로 끝나야 하며, 정상 관측 완료는 이 시나리오에서 실패다. 어느 쪽이
      // 먼저 오는지는 스케줄에 달려 있어 둘 다 허용한다.
      assertThat(recording.awaitReadersExited(Duration.ofSeconds(5)))
          .as("모든 reader 작업 본문이 종료됐다")
          .isTrue();
      for (int reader = 0; reader < ConcurrentRecording.READERS; reader++) {
        ConcurrentRecording.ReaderOutcome outcome = recording.readerOutcome(reader);
        assertThat(outcome).as("reader %d의 종료 기록", reader).isNotNull();
        assertThat(outcome.reachedLoop()).as("reader %d가 반복 관측 단계에 도달했다", reader).isTrue();
        assertThat(outcome.failure())
            .as("reader %d는 취소 또는 제한 시간으로 끝난다", reader)
            .isNotNull()
            .isInstanceOfAny(InterruptedException.class, TimeoutException.class);
      }
    } finally {
      // 검증 결과와 무관하게 멈춰 둔 writer를 푼다. 정리 결과는 여기서 단언하지 않고 기록만 해 앞선 실패를 덮지 않는다.
      release.countDown();
      terminatedAfterRelease = recording.awaitTermination(Duration.ofSeconds(5));
    }
    // 취소된 Future의 get이 아니라 실행기 종료로 확인한다 — 멈춰 있던 writer를 포함한 모든 작업 본문이 실제로 끝났다.
    assertThat(terminatedAfterRelease).as("writer 해제 뒤 모든 작업과 실행기가 실제 종료됐다").isTrue();
  }

  /**
   * writer 여러 개가 고유한 합성 값을 절반씩 두 단계로 기록하고, reader는 두 단계 사이에서 한 번 결정적으로 관측한 뒤 후반 기록 중에는 반복 관측한다.
   *
   * <p>시작 게이트를 함께 푸는 것만으로는 읽기와 쓰기가 실제로 겹친다고 보장할 수 없다. 그래서 전반 기록 완료 → reader 관측 → 후반 기록 순서를 latch로
   * 강제해, 최소 한 번의 조회·스냅샷 생성이 전반 완료·후반 시작 전의 안정된 구간에서 일어나게 한다. 후반 기록 중의 반복 관측은 겹칠 수 있으나 겹침 자체를 단정하지
   * 않으며, 관측값의 범위와 내용만 검사한다.
   *
   * <p>실패·취소·정리 계약: 작업 하나가 실패하면 남은 작업을 협력적으로 취소하고 실행기를 종료한다. 최초 실패 원인이 timeout·interrupt·취소·정리 결과에
   * 덮이지 않도록, 그런 부수 실패와 실행기 미종료 사실은 suppressed로 보존한다. 이 시나리오가 소유한 작업의 협력적 종료만 검증하며, 임의의 작업을 강제로 끝낼 수
   * 있다고 가정하지 않는다.
   *
   * <p>기록 규모는 작게 잡는다. 이 fixture는 테스트 대역이라 대량 복사가 필요 없다.
   */
  private static final class ConcurrentRecording {

    private static final int WRITERS = 4;
    private static final int RECORDS_PER_WRITER = 32;
    private static final int FIRST_HALF = RECORDS_PER_WRITER / 2;
    private static final int READERS = 2;
    private static final int TOTAL = WRITERS * RECORDS_PER_WRITER;
    private static final int AFTER_FIRST_HALF = WRITERS * FIRST_HALF;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final String EXECUTOR_NOT_TERMINATED = "실행기가 제한 시간 안에 종료·정리되지 않았습니다.";

    /** reader 작업 본문이 finally에서 남기는 종료 기록. Future 취소 상태와 달리 본문이 실제로 끝났음을 뜻한다. */
    record ReaderOutcome(boolean reachedLoop, Throwable failure) {}

    private final String prefix;
    private final Consumer<String> record;
    private final IntSupplier count;
    private final Supplier<List<String>> snapshot;
    private final Set<String> expected;
    private final long timeoutNanos;
    private final CountDownLatch readersExited = new CountDownLatch(READERS);
    private final AtomicReferenceArray<ReaderOutcome> readerOutcomes =
        new AtomicReferenceArray<>(READERS);
    private ExecutorService executor;

    ConcurrentRecording(
        String prefix,
        Consumer<String> record,
        IntSupplier count,
        Supplier<List<String>> snapshot) {
      this(prefix, record, count, snapshot, DEFAULT_TIMEOUT);
    }

    /** 실패 경로 테스트가 짧은 제한 시간으로 실행할 수 있게 한다. */
    ConcurrentRecording(
        String prefix,
        Consumer<String> record,
        IntSupplier count,
        Supplier<List<String>> snapshot,
        Duration timeout) {
      this.prefix = prefix;
      this.record = record;
      this.count = count;
      this.snapshot = snapshot;
      this.timeoutNanos = timeout.toNanos();
      Set<String> values = new LinkedHashSet<>();
      for (int writer = 0; writer < WRITERS; writer++) {
        for (int index = 0; index < RECORDS_PER_WRITER; index++) {
          values.add(value(prefix, writer, index));
        }
      }
      this.expected = Set.copyOf(values);
    }

    /** 모든 값이 서로 달라야 누락과 잘못된 중복을 값 단위로 판정할 수 있다. */
    static String value(String prefix, int writer, int index) {
      return prefix + "-w" + writer + "-" + index;
    }

    static boolean isExecutorNotTerminated(Throwable failure) {
      return failure instanceof AssertionError
          && EXECUTOR_NOT_TERMINATED.equals(failure.getMessage());
    }

    /** 모든 reader 작업 본문이 끝날 때까지 제한 시간 안에서 기다린다. */
    boolean awaitReadersExited(Duration timeout) throws InterruptedException {
      return this.readersExited.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** {@code null}이면 그 reader의 본문이 아직 끝나지 않았다. */
    ReaderOutcome readerOutcome(int reader) {
      return this.readerOutcomes.get(reader);
    }

    /** {@link #run()} 뒤에 추가로 기다린다. 의도적으로 멈춰 둔 작업을 푼 뒤 실제 종료를 확인하는 데 쓴다. */
    boolean awaitTermination(Duration timeout) throws InterruptedException {
      return this.executor != null
          && this.executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    void run() throws Exception {
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch firstHalfWritten = new CountDownLatch(WRITERS);
      CountDownLatch midReadDone = new CountDownLatch(READERS);
      CountDownLatch writersDone = new CountDownLatch(WRITERS);

      List<Callable<Integer>> tasks = new ArrayList<>();
      for (int writer = 0; writer < WRITERS; writer++) {
        tasks.add(writer(writer, start, firstHalfWritten, midReadDone, writersDone));
      }
      for (int reader = 0; reader < READERS; reader++) {
        tasks.add(reader(reader, start, firstHalfWritten, midReadDone, writersDone));
      }

      this.executor = Executors.newFixedThreadPool(tasks.size());
      ExecutorService executor = this.executor;
      List<Future<Integer>> futures = List.of();
      List<Throwable> failures = new ArrayList<>();
      List<Integer> readerObservations = new ArrayList<>();
      boolean terminated = false;
      boolean testThreadInterrupted = false;
      try {
        futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        for (Future<Integer> future : futures) {
          try {
            Integer observations = future.get(this.timeoutNanos, TimeUnit.NANOSECONDS);
            if (observations != null) {
              readerObservations.add(observations);
            }
          } catch (ExecutionException e) {
            failures.add(e.getCause());
            cancelRemaining(futures);
          } catch (TimeoutException e) {
            failures.add(e);
            cancelRemaining(futures);
          } catch (CancellationException e) {
            // 앞선 실패로 취소된 작업. 관측 성공이 아니므로 부수 실패로 남긴다.
            failures.add(e);
          }
        }
      } catch (InterruptedException e) {
        // 테스트 스레드 자체가 인터럽트됐다. 작업을 취소하고 정리한 뒤 인터럽트 상태를 되살린다.
        testThreadInterrupted = true;
        failures.add(e);
        cancelRemaining(futures);
      } finally {
        executor.shutdownNow();
        try {
          terminated = executor.awaitTermination(this.timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          testThreadInterrupted = true;
          failures.add(e);
        }
        if (testThreadInterrupted) {
          Thread.currentThread().interrupt();
        }
      }
      failIfNeeded(failures, terminated);
      assertThat(readerObservations)
          .as("reader마다 최소 한 번은 관측한다")
          .hasSize(READERS)
          .allMatch(observations -> observations >= 1);

      // 모든 작업이 끝난 뒤의 최종 상태. 순서는 단언하지 않는다.
      assertThat(this.count.getAsInt()).as("종료 후 호출 횟수").isEqualTo(TOTAL);
      assertThat(this.snapshot.get())
          .as("종료 후 기록 — 값이 전부 고유하므로 누락과 잘못된 중복이 곧바로 드러난다")
          .containsExactlyInAnyOrderElementsOf(this.expected);
    }

    /** 아직 끝나지 않은 작업에 협력적 취소(인터럽트)를 요청한다. 작업이 응답하는지는 실행기 종료 결과로 드러난다. */
    private static void cancelRemaining(List<Future<Integer>> futures) {
      for (Future<Integer> future : futures) {
        if (!future.isDone()) {
          future.cancel(true);
        }
      }
    }

    private Callable<Integer> writer(
        int writer,
        CountDownLatch start,
        CountDownLatch firstHalfWritten,
        CountDownLatch midReadDone,
        CountDownLatch writersDone) {
      return () -> {
        try {
          await(start);
          try {
            for (int index = 0; index < FIRST_HALF; index++) {
              this.record.accept(value(this.prefix, writer, index));
            }
          } finally {
            // 실패해도 reader가 영원히 기다리지 않게 한다. 실패 자체는 Future로 전달된다.
            firstHalfWritten.countDown();
          }
          await(midReadDone);
          for (int index = FIRST_HALF; index < RECORDS_PER_WRITER; index++) {
            this.record.accept(value(this.prefix, writer, index));
          }
          return null;
        } finally {
          writersDone.countDown();
        }
      };
    }

    private Callable<Integer> reader(
        int reader,
        CountDownLatch start,
        CountDownLatch firstHalfWritten,
        CountDownLatch midReadDone,
        CountDownLatch writersDone) {
      return () -> {
        boolean reachedLoop = false;
        Throwable failure = null;
        try {
          await(start);
          await(firstHalfWritten);
          int observations = 0;
          try {
            // 1단계: 모든 writer가 전반을 끝내고 후반 시작 전에 멈춰 있는 안정된 구간. 쓰기가 진행 중이 아니므로 값이 결정적이다.
            int midCount = this.count.getAsInt();
            List<String> midSnapshot = this.snapshot.get();
            assertThat(midCount).as("전반 기록 직후 호출 횟수").isEqualTo(AFTER_FIRST_HALF);
            assertThat(midSnapshot).as("전반 기록 직후 스냅샷 크기").hasSize(AFTER_FIRST_HALF);
            assertWithinExpected(midSnapshot);
            observations++;
          } finally {
            midReadDone.countDown();
          }
          // 2단계: 후반 기록과 겹칠 수 있는 반복 관측. 겹침을 보장하지 않으며, 범위와 내용만 검사한다.
          // 호출 횟수와 스냅샷 크기는 서로 다른 시점의 값이므로 서로 같다고 단언하지 않는다.
          // 취소(인터럽트)와 자체 제한 시간은 관측 완료가 아니라 실패로 끝낸다 — writer가 멈추면 이 둘만이 탈출구다.
          reachedLoop = true;
          long deadline = System.nanoTime() + this.timeoutNanos;
          while (writersDone.getCount() > 0) {
            if (Thread.currentThread().isInterrupted()) {
              throw new InterruptedException("reader 관측이 취소되었습니다.");
            }
            if (System.nanoTime() - deadline >= 0) {
              throw new TimeoutException("reader가 제한 시간 안에 writer 종료를 관측하지 못했습니다.");
            }
            int observedCount = this.count.getAsInt();
            List<String> observedSnapshot = this.snapshot.get();
            assertThat(observedCount).isBetween(AFTER_FIRST_HALF, TOTAL);
            assertThat(observedSnapshot).hasSizeBetween(AFTER_FIRST_HALF, TOTAL);
            assertWithinExpected(observedSnapshot);
            observations++;
          }
          return observations;
        } catch (Throwable t) {
          failure = t;
          throw t;
        } finally {
          // Future 취소와 무관하게, 작업 본문이 어디까지 갔고 어떻게 끝났는지를 본문 스스로 남긴다.
          this.readerOutcomes.set(reader, new ReaderOutcome(reachedLoop, failure));
          this.readersExited.countDown();
        }
      };
    }

    /** 실행 중 스냅샷에는 허용된 값만, 그것도 한 번씩만 있어야 한다. */
    private void assertWithinExpected(List<String> observed) {
      assertThat(observed).doesNotHaveDuplicates();
      assertThat(this.expected).containsAll(observed);
    }

    private void await(CountDownLatch latch) throws InterruptedException, TimeoutException {
      if (!latch.await(this.timeoutNanos, TimeUnit.NANOSECONDS)) {
        throw new TimeoutException("단계 동기화 대기 시간을 초과했습니다.");
      }
    }

    /**
     * 최초 원인을 우선 보고하고 나머지는 suppressed로 보존한다.
     *
     * <p>작업 하나가 실패하면 나머지는 timeout·interrupt·취소로 끝나므로 그런 부수 실패를 원인보다 앞세우지 않는다. 실행기가 제한 시간 안에 종료되지 않은
     * 사실도 원인을 덮지 않고 함께 남긴다.
     */
    private static void failIfNeeded(List<Throwable> failures, boolean terminated) {
      if (failures.isEmpty() && terminated) {
        return;
      }
      Throwable primary =
          failures.stream()
              .filter(
                  failure ->
                      !(failure instanceof TimeoutException)
                          && !(failure instanceof InterruptedException)
                          && !(failure instanceof CancellationException))
              .findFirst()
              .orElse(failures.isEmpty() ? null : failures.get(0));
      AssertionError error =
          primary == null
              ? new AssertionError(EXECUTOR_NOT_TERMINATED)
              : new AssertionError("동시 기록 작업이 실패했습니다: " + primary, primary);
      failures.stream().filter(failure -> failure != primary).forEach(error::addSuppressed);
      if (!terminated && primary != null) {
        error.addSuppressed(new AssertionError(EXECUTOR_NOT_TERMINATED));
      }
      throw error;
    }
  }
}
