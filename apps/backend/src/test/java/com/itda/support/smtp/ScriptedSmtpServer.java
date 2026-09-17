package com.itda.support.smtp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * 루프백에서만 듣는 대본형 SMTP 테스트 서버.
 *
 * <p>진짜 소켓·진짜 TLS로 동작한다. 제공자 adapter가 "어느 단계에서 끊겼는가·서버가 무엇을 받았는가·소켓이 실제로 닫혔는가"를 분류하는 코드이므로, mock으로는
 * 그 경계를 검증할 수 없다. 외부로 나가는 연결은 없다.
 *
 * <p>연결마다 {@link Transcript}를 남긴다. 받은 명령(그 시점의 TLS 여부 포함)·본문·AUTH 자격증명·상대가 먼저 끊었는지를 기록한다. 이 기록은 테스트
 * 메모리에만 있고 어디에도 출력하지 않는다.
 */
public final class ScriptedSmtpServer implements AutoCloseable {

  private static final AtomicInteger SEQUENCE = new AtomicInteger();

  private final ServerSocket serverSocket;
  private final SmtpScript script;
  private final Thread acceptThread;
  private final List<Transcript> transcripts = new CopyOnWriteArrayList<>();
  private final List<Socket> clients = new CopyOnWriteArrayList<>();
  private final List<Thread> handlers = new CopyOnWriteArrayList<>();
  private volatile boolean running = true;

  private ScriptedSmtpServer(ServerSocket serverSocket, SmtpScript script) {
    this.serverSocket = serverSocket;
    this.script = script;
    this.acceptThread =
        new Thread(this::acceptLoop, "scripted-smtp-accept-" + SEQUENCE.incrementAndGet());
    this.acceptThread.setDaemon(true);
  }

  /** 평문 listener. STARTTLS는 대본이 정한다. */
  public static ScriptedSmtpServer start(SmtpScript script) throws IOException {
    ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    ScriptedSmtpServer server = new ScriptedSmtpServer(socket, script);
    server.acceptThread.start();
    return server;
  }

  /** 연결 시작부터 TLS인 listener (implicit TLS). */
  public static ScriptedSmtpServer startImplicitTls(SmtpScript script, SSLContext context)
      throws IOException {
    SSLServerSocket socket =
        (SSLServerSocket)
            context
                .getServerSocketFactory()
                .createServerSocket(0, 50, InetAddress.getLoopbackAddress());
    socket.setUseClientMode(false);
    ScriptedSmtpServer server = new ScriptedSmtpServer(socket, script);
    server.acceptThread.start();
    return server;
  }

  public String host() {
    return this.serverSocket.getInetAddress().getHostAddress();
  }

  public int port() {
    return this.serverSocket.getLocalPort();
  }

  public List<Transcript> transcripts() {
    return List.copyOf(this.transcripts);
  }

  public int connectionCount() {
    return this.transcripts.size();
  }

  /** {@code index}번째 연결이 들어올 때까지 기다린다. 처리가 끝나기를 기다리지는 않는다. */
  public Transcript awaitConnection(int index, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (this.transcripts.size() <= index) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("연결 " + index + "이(가) 들어오지 않았습니다.");
      }
      Thread.sleep(10);
    }
    return this.transcripts.get(index);
  }

  /** {@code index}번째 연결의 처리가 끝날 때까지(상대가 끊었거나 대본이 끝났거나) 기다린다. */
  public Transcript awaitFinished(int index, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (this.transcripts.size() <= index) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("연결 " + index + "이(가) 들어오지 않았습니다.");
      }
      Thread.sleep(10);
    }
    Transcript transcript = this.transcripts.get(index);
    long remaining = deadline - System.nanoTime();
    if (!transcript.finished.await(Math.max(remaining, 1), TimeUnit.NANOSECONDS)) {
      throw new AssertionError("연결 " + index + "의 처리가 제한 시간 안에 끝나지 않았습니다.");
    }
    return transcript;
  }

  @Override
  public void close() {
    this.running = false;
    closeQuietly(this.serverSocket);
    for (Socket client : this.clients) {
      closeQuietly(client);
    }
    try {
      this.acceptThread.join(3_000);
      for (Thread handler : this.handlers) {
        handler.join(3_000);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void acceptLoop() {
    while (this.running) {
      Socket socket;
      try {
        socket = this.serverSocket.accept();
      } catch (IOException e) {
        return; // listener가 닫혔다
      }
      this.clients.add(socket);
      Transcript transcript = new Transcript();
      this.transcripts.add(transcript);
      Thread handler =
          new Thread(
              () -> handle(socket, transcript), "scripted-smtp-conn-" + SEQUENCE.incrementAndGet());
      handler.setDaemon(true);
      this.handlers.add(handler);
      handler.start();
    }
  }

  private void handle(Socket socket, Transcript transcript) {
    Connection connection = new Connection(socket, socket instanceof SSLSocket);
    try {
      transcript.tlsAtStart = connection.tls;
      if (!respond(connection, transcript, SmtpScript.Step.GREETING, "220 itda-test ESMTP")) {
        return;
      }
      while (true) {
        String line = connection.readLine();
        if (line == null) {
          transcript.peerClosed = true;
          return;
        }
        transcript.commands.add(new Command(line, connection.tls));
        String verb = verbOf(line);
        switch (verb) {
          case "EHLO" -> {
            if (!respond(connection, transcript, SmtpScript.Step.EHLO, ehloReply(connection))) {
              return;
            }
          }
          case "HELO" -> connection.reply("250 itda-test");
          case "STARTTLS" -> {
            if (this.script.startTlsContext == null || connection.tls) {
              connection.reply("454 TLS not available");
              continue;
            }
            if (!respond(
                connection, transcript, SmtpScript.Step.STARTTLS, "220 Ready to start TLS")) {
              return;
            }
            // 454 등 거부 응답 뒤에는 TLS handshake를 시작하지 않는다.
            if (!isSuccess(this.script.replies.get(SmtpScript.Step.STARTTLS), "220")) {
              continue;
            }
            try {
              connection.upgradeTls(this.script.startTlsContext);
            } catch (IOException e) {
              transcript.tlsHandshakeFailed = true;
              return;
            }
          }
          case "AUTH" -> {
            if (!authenticate(connection, transcript, line)) {
              return;
            }
          }
          case "MAIL" -> {
            if (!respond(connection, transcript, SmtpScript.Step.MAIL, "250 OK")) {
              return;
            }
          }
          case "RCPT" -> {
            if (!respond(connection, transcript, SmtpScript.Step.RCPT, "250 OK")) {
              return;
            }
          }
          case "DATA" -> {
            if (!respond(
                connection,
                transcript,
                SmtpScript.Step.DATA,
                "354 End data with <CR><LF>.<CR><LF>")) {
              return;
            }
            if (!isSuccess(this.script.replies.get(SmtpScript.Step.DATA), "354")) {
              continue; // 본문을 받지 않는다
            }
            String payload = connection.readData();
            if (payload == null) {
              transcript.peerClosed = true;
              return;
            }
            transcript.messages.add(payload);
            if (!respond(connection, transcript, SmtpScript.Step.END_OF_DATA, "250 OK queued")) {
              return;
            }
          }
          case "RSET", "NOOP" -> connection.reply("250 OK");
          case "QUIT" -> {
            respond(connection, transcript, SmtpScript.Step.QUIT, "221 Bye");
            return;
          }
          default -> connection.reply("500 Unrecognized command");
        }
      }
    } catch (IOException e) {
      transcript.ioFailure = true;
    } finally {
      closeQuietly(socket);
      transcript.finished.countDown();
    }
  }

  private static boolean isSuccess(String override, String defaultCode) {
    return override == null || override.startsWith(defaultCode);
  }

  /**
   * 한 단계의 응답을 대본대로 처리한다.
   *
   * @return 대화를 계속해도 되면 {@code true}. 끊었거나 붙들다 상대가 끊었으면 {@code false}
   */
  private boolean respond(
      Connection connection, Transcript transcript, SmtpScript.Step step, String defaultReply)
      throws IOException {
    Duration delay = this.script.delays.get(step);
    if (delay != null) {
      try {
        Thread.sleep(delay.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    if (this.script.hangs.contains(step)) {
      transcript.hungAt = step;
      // 응답하지 않는다. 상대가 끊을 때까지 읽기만 하다가 EOF를 본다.
      while (connection.readLine() != null) {
        // 붙든 동안 들어온 줄은 기록하지 않는다 — 이 단계의 계약은 "응답 없음"이다.
      }
      transcript.peerClosed = true;
      return false;
    }
    if (this.script.drops.contains(step)) {
      transcript.droppedAt = step;
      connection.close();
      return false;
    }
    connection.reply(this.script.replies.getOrDefault(step, defaultReply));
    return true;
  }

  private String ehloReply(Connection connection) {
    String override = this.script.replies.get(SmtpScript.Step.EHLO);
    if (override != null) {
      return override;
    }
    List<String> lines = new ArrayList<>();
    lines.add("itda-test greets you");
    lines.add("8BITMIME");
    if (this.script.advertiseAuth) {
      lines.add("AUTH PLAIN LOGIN");
    }
    if (this.script.startTlsContext != null && !connection.tls) {
      lines.add("STARTTLS");
    }
    lines.add("OK");
    StringBuilder reply = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      boolean last = i == lines.size() - 1;
      reply.append("250").append(last ? ' ' : '-').append(lines.get(i));
      if (!last) {
        reply.append("\r\n");
      }
    }
    return reply.toString();
  }

  /** AUTH LOGIN(대화형)과 AUTH PLAIN(초기 응답 포함)을 받는다. 자격증명은 transcript에만 남긴다. */
  private boolean authenticate(Connection connection, Transcript transcript, String line)
      throws IOException {
    String[] parts = line.trim().split("\\s+");
    String mechanism = parts.length > 1 ? parts[1].toUpperCase(Locale.ROOT) : "";
    if ("LOGIN".equals(mechanism)) {
      String user;
      if (parts.length > 2) {
        user = decode(parts[2]);
      } else {
        connection.reply("334 VXNlcm5hbWU6");
        String userLine = connection.readLine();
        if (userLine == null) {
          transcript.peerClosed = true;
          return false;
        }
        user = decode(userLine);
      }
      connection.reply("334 UGFzc3dvcmQ6");
      String passLine = connection.readLine();
      if (passLine == null) {
        transcript.peerClosed = true;
        return false;
      }
      transcript.authUsername = user;
      transcript.authPassword = decode(passLine);
    } else if ("PLAIN".equals(mechanism)) {
      String initial;
      if (parts.length > 2) {
        initial = parts[2];
      } else {
        connection.reply("334 ");
        initial = connection.readLine();
        if (initial == null) {
          transcript.peerClosed = true;
          return false;
        }
      }
      String[] fields = decode(initial).split("\0", -1);
      transcript.authUsername = fields.length > 1 ? fields[1] : null;
      transcript.authPassword = fields.length > 2 ? fields[2] : null;
    } else {
      connection.reply("504 Unrecognized authentication type");
      return true;
    }
    return respond(
        connection, transcript, SmtpScript.Step.AUTH, "235 2.7.0 Authentication successful");
  }

  private static String decode(String base64) {
    try {
      return new String(Base64.getDecoder().decode(base64.trim()), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  private static String verbOf(String line) {
    int space = line.indexOf(' ');
    String verb = space < 0 ? line : line.substring(0, space);
    int colon = verb.indexOf(':');
    if (colon >= 0) {
      verb = verb.substring(0, colon);
    }
    return verb.toUpperCase(Locale.ROOT);
  }

  private static void closeQuietly(java.io.Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException ignored) {
      // 테스트 정리 단계다
    }
  }

  /** 받은 명령 한 줄과 그 시점에 TLS였는지. */
  public record Command(String line, boolean overTls) {}

  /** 연결 하나의 기록. 서버 스레드가 쓰고 테스트 스레드가 읽는다. */
  public static final class Transcript {
    private final CountDownLatch finished = new CountDownLatch(1);
    private final List<Command> commands = new CopyOnWriteArrayList<>();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private volatile boolean tlsAtStart;
    private volatile boolean tlsHandshakeFailed;
    private volatile boolean peerClosed;
    private volatile boolean ioFailure;
    private volatile SmtpScript.Step hungAt;
    private volatile SmtpScript.Step droppedAt;
    private volatile String authUsername;
    private volatile String authPassword;

    public List<Command> commands() {
      return List.copyOf(this.commands);
    }

    /** 받은 명령의 동사만 순서대로. */
    public List<String> verbs() {
      return this.commands.stream().map(c -> verbOf(c.line())).toList();
    }

    public List<Command> commandsOf(String verb) {
      return this.commands.stream().filter(c -> verbOf(c.line()).equals(verb)).toList();
    }

    /** 받은 본문(헤더 포함) 원문. dot-stuffing은 풀어 둔 상태다. */
    public List<String> messages() {
      return List.copyOf(this.messages);
    }

    public boolean tlsAtStart() {
      return this.tlsAtStart;
    }

    public boolean tlsHandshakeFailed() {
      return this.tlsHandshakeFailed;
    }

    /** 상대(제공자 adapter)가 먼저 연결을 끊었는지. */
    public boolean peerClosed() {
      return this.peerClosed;
    }

    public boolean ioFailure() {
      return this.ioFailure;
    }

    public SmtpScript.Step hungAt() {
      return this.hungAt;
    }

    public SmtpScript.Step droppedAt() {
      return this.droppedAt;
    }

    public String authUsername() {
      return this.authUsername;
    }

    public String authPassword() {
      return this.authPassword;
    }

    public boolean isFinished() {
      return this.finished.getCount() == 0;
    }
  }

  /** 소켓 하나의 읽기·쓰기. STARTTLS로 스트림이 바뀔 수 있어 소켓 참조를 갈아 끼운다. */
  private static final class Connection {
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private boolean tls;

    Connection(Socket socket, boolean tls) {
      this.socket = socket;
      this.tls = tls;
    }

    private void ensureStreams() throws IOException {
      if (this.in == null) {
        this.in = this.socket.getInputStream();
        this.out = this.socket.getOutputStream();
      }
    }

    void reply(String text) throws IOException {
      ensureStreams();
      this.out.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
      this.out.flush();
    }

    /** CRLF(또는 LF)까지 한 줄. EOF면 {@code null}. */
    String readLine() throws IOException {
      ensureStreams();
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      int b;
      boolean any = false;
      while ((b = this.in.read()) != -1) {
        any = true;
        if (b == '\n') {
          break;
        }
        if (b != '\r') {
          buffer.write(b);
        }
      }
      if (!any) {
        return null;
      }
      return buffer.toString(StandardCharsets.UTF_8);
    }

    /** DATA 본문을 {@code .} 한 줄까지 읽는다. dot-stuffing을 푼다. EOF면 {@code null}. */
    String readData() throws IOException {
      StringBuilder payload = new StringBuilder();
      while (true) {
        String line = readLine();
        if (line == null) {
          return null;
        }
        if (line.equals(".")) {
          return payload.toString();
        }
        payload.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
      }
    }

    void upgradeTls(SSLContext context) throws IOException {
      SSLSocket ssl =
          (SSLSocket)
              context
                  .getSocketFactory()
                  .createSocket(
                      this.socket,
                      this.socket.getInetAddress().getHostAddress(),
                      this.socket.getPort(),
                      true);
      ssl.setUseClientMode(false);
      ssl.startHandshake();
      this.socket = ssl;
      this.in = ssl.getInputStream();
      this.out = ssl.getOutputStream();
      this.tls = true;
    }

    void close() throws IOException {
      this.socket.close();
    }
  }
}
