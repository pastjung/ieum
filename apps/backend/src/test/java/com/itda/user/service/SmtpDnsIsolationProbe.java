package com.itda.user.service;

import com.itda.support.smtp.BlockingDnsResolver;
import com.itda.support.smtp.ScriptedSmtpServer;
import com.itda.support.smtp.SmtpScript;
import com.itda.user.domain.AccountActionPurpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;

/** SmtpDnsIsolationTest가 별도 JVM으로 실행한다. 일반 테스트 JVM의 DNS는 변경하지 않는다. */
public final class SmtpDnsIsolationProbe {
  private SmtpDnsIsolationProbe() {}

  public static void main(String[] args) throws Exception {
    // 이 자식 JVM에서만 캐시를 끈다. 앞선 Service 조회가 SocketFetcher의 실제 조회를 가리지 않게 한다.
    java.security.Security.setProperty("networkaddress.cache.ttl", "0");
    boolean shutdown = args[0].equals("shutdown");
    BlockingDnsResolver.mode = shutdown ? "server" : args[0];
    boolean remote = shutdown || args[0].equals("server") || args[0].equals("socket");
    ExecutorService caller = Executors.newFixedThreadPool(4);
    ObservingClock clock = new ObservingClock();
    try (ScriptedSmtpServer server = ScriptedSmtpServer.start(SmtpScript.standard().withoutAuth());
        SmtpMailProvider provider =
            new SmtpMailProvider(
                new MailProviderProperties(
                    remote ? "smtp-test.invalid" : "127.0.0.1",
                    server.port(),
                    MailProviderProperties.Security.PLAINTEXT,
                    null,
                    null,
                    "sender@example.invalid",
                    null,
                    null,
                    Duration.ofSeconds(20),
                    Duration.ofSeconds(20),
                    Duration.ofSeconds(20)),
                new MailLinkProperties(
                    "https://app.example.invalid/verify", "https://app.example.invalid/reset"),
                Duration.ofSeconds(shutdown ? 30 : 1),
                clock)) {
      try {
        MailProvider.Message message =
            new MailProvider.Message(
                AccountActionPurpose.EMAIL_VERIFICATION,
                "recipient@example.invalid",
                "header.payload.signature");
        long started = System.nanoTime();
        Future<MailProvider.SendOutcome> result =
            caller.submit(
                () -> {
                  MDC.put("requestId", "dns-probe-first");
                  try {
                    return provider.send(message);
                  } finally {
                    MDC.clear();
                  }
                });
        require(BlockingDnsResolver.ENTERED.await(5, TimeUnit.SECONDS), "실제 DNS 경로 진입");
        require(BlockingDnsResolver.actualAngusPath, "실제 Angus 호출 스택");
        require(
            clock.requestIds.size() == 1 && clock.requestIds.get(0).equals("dns-probe-first"),
            "호출 문맥 전달");
        if (shutdown) {
          provider.abortInFlight();
        }
        require(
            result.get(5, TimeUnit.SECONDS).result() == MailProvider.SendResult.RETRYABLE,
            "본문 전 시간 초과는 RETRYABLE");
        require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5), "호출자 전체 제한");
        require(!provider.awaitIdle(Duration.ofMillis(50)), "반환 뒤 실제 작업은 남음");
        List<Future<MailProvider.SendOutcome>> excess = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
          excess.add(caller.submit(() -> provider.send(message)));
        }
        for (Future<MailProvider.SendOutcome> additional : excess) {
          require(
              additional.get(5, TimeUnit.SECONDS).result() == MailProvider.SendResult.RETRYABLE,
              "점유 중 동시 추가 호출 거부");
        }
        require(BlockingDnsResolver.BLOCKS.get() == 1, "새 DNS 작업을 쌓지 않음");
        require(
            Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.getName().startsWith("mail-smtp-execution-"))
                    .count()
                == 1,
            "실행 스레드 상한 1");
        if (shutdown) {
          long closeStarted = System.nanoTime();
          provider.close();
          require(System.nanoTime() - closeStarted < TimeUnit.SECONDS.toNanos(8), "종료 대기 상한");
          require(!provider.awaitIdle(Duration.ofMillis(50)), "close 반환은 DNS 실제 종료가 아님");
          boolean rejected = false;
          try {
            provider.send(message);
          } catch (IllegalStateException expected) {
            rejected = true;
          }
          require(rejected, "닫힌 provider가 새 작업을 받지 않음");
        }
        BlockingDnsResolver.RELEASE.countDown();
        require(provider.awaitIdle(Duration.ofSeconds(5)), "DNS 해제 뒤 실제 작업 정리");
        require(BlockingDnsResolver.INTERRUPTS.get() > 0, "취소 interrupt를 무시한 DNS 검증");
        int firstConnections = remote ? 0 : 1;
        require(server.connectionCount() == firstConnections, "늦은 신규 연결 없음");
        if (firstConnections == 1) {
          require(
              server.awaitFinished(0, Duration.ofSeconds(5)).commands().isEmpty(),
              "로컬 이름 조회 해제 후 늦은 EHLO·발송 없음");
        }
        if (!shutdown) {
          clock.requestIds.clear();
          require(
              provider.send(message).result() == MailProvider.SendResult.ACCEPTED,
              "실제 정리 후 다음 발송 복구");
          require(
              server.awaitFinished(firstConnections, Duration.ofSeconds(5)).messages().size() == 1,
              "복구 발송만 한 번 수신");
          require(
              clock.requestIds.size() == 1 && clock.requestIds.get(0) == null,
              "다음 작업에 이전 MDC가 남지 않음");
        }
      } finally {
        BlockingDnsResolver.RELEASE.countDown();
        require(provider.awaitIdle(Duration.ofSeconds(5)), "실패 경로도 DNS 작업 정리");
      }
    } finally {
      BlockingDnsResolver.RELEASE.countDown();
      caller.shutdownNow();
      require(caller.awaitTermination(5, TimeUnit.SECONDS), "호출 실행기 정리");
    }
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.getName().startsWith("mail-smtp-")) {
        thread.join(5_000);
      }
    }
    require(
        Thread.getAllStackTraces().keySet().stream()
            .noneMatch(thread -> thread.getName().startsWith("mail-smtp-")),
        "provider 실제 종료");
    System.out.println("DNS_ISOLATION_OK");
  }

  private static void require(boolean condition, String label) {
    if (!condition) {
      throw new AssertionError(label);
    }
  }

  /** DNS 캐시와 무관하게 매 발송의 메시지 작성 시점에서 실행 스레드 문맥을 관측한다. */
  private static final class ObservingClock extends Clock {
    private final CopyOnWriteArrayList<String> requestIds = new CopyOnWriteArrayList<>();

    @Override
    public Instant instant() {
      this.requestIds.add(MDC.get("requestId"));
      return Instant.now();
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }
  }
}
