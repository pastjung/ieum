package com.itda.support.smtp;

import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;

/**
 * {@link ScriptedSmtpServer}가 연결마다 따르는 대본.
 *
 * <p>정상 흐름을 기본으로 두고, 단계별로 응답을 바꾸거나({@link #reply}), 응답 없이 끊거나({@link #dropAt}), 응답하지 않고 붙들거나({@link
 * #hangAt}), 응답을 늦춘다({@link #delayAt}). 각 단계는 SMTP 대화의 한 응답 지점이다 — 제공자 adapter가 "어느 단계에서 무엇을
 * 확인했는가"로 결과를 분류하므로 테스트도 같은 단위로 서버를 조종한다.
 */
public final class SmtpScript {

  /** 서버가 응답하는 지점. */
  public enum Step {
    GREETING,
    EHLO,
    STARTTLS,
    AUTH,
    MAIL,
    RCPT,
    DATA,
    END_OF_DATA,
    QUIT
  }

  final Map<Step, String> replies = new EnumMap<>(Step.class);
  final Set<Step> drops = EnumSet.noneOf(Step.class);
  final Set<Step> hangs = EnumSet.noneOf(Step.class);
  final Map<Step, Duration> delays = new EnumMap<>(Step.class);
  boolean advertiseAuth = true;
  SSLContext startTlsContext;

  private SmtpScript() {}

  /** 인사·EHLO(AUTH PLAIN LOGIN 광고)·AUTH 성공·MAIL·RCPT·DATA·본문 접수·QUIT까지 모두 정상 응답하는 대본. */
  public static SmtpScript standard() {
    return new SmtpScript();
  }

  /** 그 단계의 응답을 바꾼다. 여러 줄 응답은 {@code \r\n}으로 잇는다. */
  public SmtpScript reply(Step step, String reply) {
    this.replies.put(step, reply);
    return this;
  }

  /** 그 단계에서 응답 없이 연결을 끊는다. {@link Step#END_OF_DATA}면 본문을 다 받은 뒤 끊는다. */
  public SmtpScript dropAt(Step step) {
    this.drops.add(step);
    return this;
  }

  /** 그 단계에서 응답하지 않고 상대가 끊을 때까지 연결을 붙든다. */
  public SmtpScript hangAt(Step step) {
    this.hangs.add(step);
    return this;
  }

  /** 그 단계의 응답을 늦춘다. */
  public SmtpScript delayAt(Step step, Duration delay) {
    this.delays.put(step, delay);
    return this;
  }

  /** EHLO 응답에서 AUTH 확장을 뺀다. */
  public SmtpScript withoutAuth() {
    this.advertiseAuth = false;
    return this;
  }

  /** EHLO 응답에 STARTTLS를 광고하고 STARTTLS 명령에 이 context로 TLS를 연다. */
  public SmtpScript advertiseStartTls(SSLContext serverContext) {
    this.startTlsContext = serverContext;
    return this;
  }
}
