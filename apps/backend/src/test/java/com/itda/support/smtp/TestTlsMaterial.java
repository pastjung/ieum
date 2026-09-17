package com.itda.support.smtp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * 테스트 실행 중에만 존재하는 자체 서명 TLS 자료.
 *
 * <p>인증서·키·keystore를 저장소에 두지 않는다. 실행마다 JDK {@code keytool}로 임시 디렉터리에 만들고, keystore 비밀번호는 난수로 만들어
 * 환경변수로만 넘긴다(명령줄 인자에 남기지 않는다). SAN에 {@code 127.0.0.1}·{@code localhost}를 넣어 운영과 같은 host 이름 검증을 그대로
 * 통과시킨다 — 검증을 끄는 대신 검증이 통과하는 자료를 만든다.
 *
 * <p>클라이언트 쪽 {@link #clientTrustContext()}는 이 인증서 하나만 신뢰하는 격리된 trust store다. JVM 기본 trust store는
 * 건드리지 않으며, 운영 코드의 기본 검증 경로는 바뀌지 않는다.
 */
public final class TestTlsMaterial {

  private static final String ALIAS = "itda-test-smtp";
  private static final String PASSWORD_ENV = "ITDA_TEST_TLS_KEYSTORE_PASSWORD";

  private final SSLContext serverContext;
  private final SSLContext clientTrustContext;

  private TestTlsMaterial(SSLContext serverContext, SSLContext clientTrustContext) {
    this.serverContext = serverContext;
    this.clientTrustContext = clientTrustContext;
  }

  public static TestTlsMaterial generate(Path directory)
      throws IOException, InterruptedException, GeneralSecurityException {
    byte[] random = new byte[24];
    new SecureRandom().nextBytes(random);
    String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    Path keystore = directory.resolve("smtp-test.p12");

    ProcessBuilder builder =
        new ProcessBuilder(
            List.of(
                keytool(),
                "-genkeypair",
                "-alias",
                ALIAS,
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-sigalg",
                "SHA256withRSA",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost,ip:127.0.0.1",
                "-validity",
                "2",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystore.toString(),
                "-storepass:env",
                PASSWORD_ENV));
    builder.environment().put(PASSWORD_ENV, password);
    builder.redirectErrorStream(true);
    Process process = builder.start();
    byte[] output = process.getInputStream().readAllBytes();
    int exit = process.waitFor();
    if (exit != 0 || !Files.exists(keystore)) {
      throw new IllegalStateException(
          "keytool이 테스트 인증서를 만들지 못했습니다. exit=" + exit + " output=" + new String(output));
    }

    char[] passwordChars = password.toCharArray();
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(keystore)) {
      keyStore.load(in, passwordChars);
    }
    KeyManagerFactory keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keyStore, passwordChars);
    SSLContext server = SSLContext.getInstance("TLS");
    server.init(keyManagers.getKeyManagers(), null, null);

    KeyStore trustStore = KeyStore.getInstance("PKCS12");
    trustStore.load(null, null);
    trustStore.setCertificateEntry(ALIAS, keyStore.getCertificate(ALIAS));
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trustStore);
    SSLContext client = SSLContext.getInstance("TLS");
    client.init(null, trustManagers.getTrustManagers(), null);

    return new TestTlsMaterial(server, client);
  }

  public SSLContext serverContext() {
    return this.serverContext;
  }

  public SSLContext clientTrustContext() {
    return this.clientTrustContext;
  }

  private static String keytool() {
    Path bin = Path.of(System.getProperty("java.home"), "bin");
    Path windows = bin.resolve("keytool.exe");
    return Files.exists(windows) ? windows.toString() : bin.resolve("keytool").toString();
  }
}
