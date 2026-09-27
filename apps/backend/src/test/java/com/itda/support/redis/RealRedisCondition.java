package com.itda.support.redis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * {@link RequiresRealRedis} 테스트를 실행할지 판단한다.
 *
 * <p>Spring context를 띄우기 전에 판단해야 해서 RESP inline 명령으로 직접 PING을 보낸다. 포트만 열려 있고 Redis가 아닌 경우를 통과시키지 않기
 * 위해 연결 성공이 아니라 {@code +PONG} 응답을 본다.
 *
 * <p>연결 확인은 테스트 실행당 한 번만 한다. 대기 시간은 짧게 잡는다 — 없는 서버를 기다리느라 테스트가 느려지거나 흔들리면 안 된다.
 */
public class RealRedisCondition implements ExecutionCondition {

  private static final String REQUIRE_ENV = "ITDA_REQUIRE_REAL_REDIS";
  private static final int PROBE_TIMEOUT_MILLIS = 300;
  private static final byte[] PING = "PING\r\n".getBytes(StandardCharsets.US_ASCII);
  private static final String PONG = "+PONG";

  private static volatile Boolean available;

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    if (isAvailable()) {
      return ConditionEvaluationResult.enabled("실제 Redis에 연결했습니다.");
    }
    if (Boolean.parseBoolean(System.getenv(REQUIRE_ENV))) {
      // 건너뛰지 않고 실패시킨다. 이 환경에서는 실제 Redis 검증이 통과 조건이다.
      throw new IllegalStateException(
          REQUIRE_ENV + "=true인데 Redis에 연결하지 못했습니다. 실제 Redis 통합 테스트를 건너뛸 수 없습니다.");
    }
    return ConditionEvaluationResult.disabled(
        "실제 Redis가 없어 건너뜁니다. 로컬에서 실행하려면 docker compose up -d redis 후 다시 시도해 주세요.");
  }

  private static boolean isAvailable() {
    Boolean cached = available;
    if (cached == null) {
      synchronized (RealRedisCondition.class) {
        cached = available;
        if (cached == null) {
          cached = ping();
          available = cached;
        }
      }
    }
    return cached;
  }

  private static boolean ping() {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host(), port()), PROBE_TIMEOUT_MILLIS);
      socket.setSoTimeout(PROBE_TIMEOUT_MILLIS);
      OutputStream out = socket.getOutputStream();
      out.write(PING);
      out.flush();
      return readsPong(socket.getInputStream());
    } catch (IOException | RuntimeException e) {
      // 연결 실패는 "Redis 없음"이라는 정보일 뿐이다. 원인 객체를 출력하지 않는다.
      return false;
    }
  }

  private static boolean readsPong(InputStream in) throws IOException {
    byte[] buffer = new byte[PONG.length()];
    int read = in.readNBytes(buffer, 0, buffer.length);
    return read == buffer.length && PONG.equals(new String(buffer, StandardCharsets.US_ASCII));
  }

  private static String host() {
    String value = System.getenv("REDIS_HOST");
    return value == null || value.isBlank() ? "localhost" : value;
  }

  private static int port() {
    String value = System.getenv("REDIS_PORT");
    if (value == null || value.isBlank()) {
      return 6379;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return 6379;
    }
  }
}
