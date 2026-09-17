package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.smtp.BlockingDnsResolver;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmtpDnsIsolationTest {
  @TempDir Path directory;

  @Test
  void SMTP_서버_DNS가_멈춰도_반환하고_해제_뒤_늦은_연결을_막는다() throws Exception {
    runProbe("server");
  }

  @Test
  void 실제_SocketFetcher_DNS가_멈춰도_반환하고_해제_뒤_늦은_연결을_막는다() throws Exception {
    runProbe("socket");
  }

  @Test
  void DNS_종료_미확인을_사실대로_남기고_닫힌_provider를_재시작하지_않는다() throws Exception {
    runProbe("shutdown");
    assertThat(Files.readString(this.directory.resolve("probe-output.txt")))
        .contains("executionStopped=false");
  }

  @Test
  void EHLO_로컬_이름_DNS가_멈춰도_반환하고_해제_뒤_늦은_발송을_막는다() throws Exception {
    runProbe("local");
  }

  @Test
  void EHLO_역방향_DNS가_멈춰도_반환하고_해제_뒤_늦은_발송을_막는다() throws Exception {
    runProbe("reverse");
  }

  private void runProbe(String mode) throws Exception {
    Path descriptor =
        this.directory.resolve("META-INF/services/java.net.spi.InetAddressResolverProvider");
    Files.createDirectories(descriptor.getParent());
    Files.writeString(descriptor, BlockingDnsResolver.class.getName());
    Set<String> entries = new LinkedHashSet<>();
    entries.add(this.directory.toString());
    for (ClassLoader loader = getClass().getClassLoader();
        loader != null;
        loader = loader.getParent()) {
      if (loader instanceof URLClassLoader urls) {
        for (URL url : urls.getURLs()) {
          entries.add(Path.of(url.toURI()).toString());
        }
      }
    }
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      entries.add(entry);
    }
    String classpath = String.join(File.pathSeparator, entries).replace('\\', '/');
    Path arguments = this.directory.resolve("java-arguments.txt");
    Files.writeString(
        arguments,
        "-Dstdout.encoding=UTF-8\n-Dstderr.encoding=UTF-8\n-cp\n\""
            + classpath
            + "\"\n"
            + SmtpDnsIsolationProbe.class.getName()
            + "\n"
            + mode);
    Path output = this.directory.resolve("probe-output.txt");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertThat(process.waitFor(Duration.ofSeconds(25).toMillis(), TimeUnit.MILLISECONDS))
          .as("격리된 DNS 테스트 JVM 종료")
          .isTrue();
      assertThat(process.exitValue()).as("자식 JVM 결과: %s", Files.readString(output)).isZero();
      assertThat(Files.readString(output)).contains("DNS_ISOLATION_OK");
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
      assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("자식 JVM 실제 종료").isTrue();
    }
  }
}
