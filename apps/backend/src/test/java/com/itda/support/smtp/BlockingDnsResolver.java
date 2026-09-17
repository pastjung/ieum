package com.itda.support.smtp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** 별도 테스트 JVM에서만 SPI로 등록한다. 외부 DNS 대신 루프백을 반환하고 조회 하나를 붙든다. */
public final class BlockingDnsResolver extends InetAddressResolverProvider {
  public static final CountDownLatch ENTERED = new CountDownLatch(1);
  public static final CountDownLatch RELEASE = new CountDownLatch(1);
  public static final AtomicInteger INTERRUPTS = new AtomicInteger();
  public static final AtomicInteger BLOCKS = new AtomicInteger();
  private static final AtomicBoolean FIRST = new AtomicBoolean(true);
  public static volatile String mode;
  public static volatile boolean actualAngusPath;

  @Override
  public String name() {
    return "isolated-smtp-dns-test";
  }

  @Override
  public InetAddressResolver get(Configuration configuration) {
    return new InetAddressResolver() {
      @Override
      public Stream<InetAddress> lookupByName(String host, LookupPolicy policy)
          throws UnknownHostException {
        boolean socketPath =
            Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(
                    frame ->
                        frame.getClassName().equals("org.eclipse.angus.mail.util.SocketFetcher"));
        if ((("server".equals(mode) || ("socket".equals(mode) && socketPath))
                && host.equals("smtp-test.invalid"))
            || ("local".equals(mode) && host.equals(configuration.lookupLocalHostName()))) {
          block();
        }
        return Stream.of(InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1}));
      }

      @Override
      public String lookupByAddress(byte[] address) throws UnknownHostException {
        if ("reverse".equals(mode)) {
          block();
        }
        return "local-test.invalid";
      }
    };
  }

  private static void block() throws UnknownHostException {
    if (!Thread.currentThread().getName().startsWith("mail-smtp-execution-")
        || !FIRST.compareAndSet(true, false)) {
      return;
    }
    actualAngusPath =
        Arrays.stream(Thread.currentThread().getStackTrace())
            .anyMatch(
                frame ->
                    "server".equals(mode)
                        ? frame.getClassName().startsWith("jakarta.mail.")
                        : "socket".equals(mode)
                            ? frame
                                .getClassName()
                                .equals("org.eclipse.angus.mail.util.SocketFetcher")
                            : frame
                                    .getClassName()
                                    .equals("org.eclipse.angus.mail.smtp.SMTPTransport")
                                && frame.getMethodName().equals("getLocalHost"));
    BLOCKS.incrementAndGet();
    ENTERED.countDown();
    // interrupt로 종료되지 않는 native DNS를 흉내 낸다. 하네스 자체에는 탈출 상한을 둔다.
    long started = System.nanoTime();
    while (RELEASE.getCount() != 0) {
      long remaining = TimeUnit.SECONDS.toNanos(30) - (System.nanoTime() - started);
      if (remaining <= 0) {
        throw new UnknownHostException("테스트 DNS 해제 제한 시간 초과");
      }
      try {
        RELEASE.await(remaining, TimeUnit.NANOSECONDS);
      } catch (InterruptedException ignored) {
        INTERRUPTS.incrementAndGet();
      }
    }
  }
}
