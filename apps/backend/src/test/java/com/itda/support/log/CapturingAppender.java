package com.itda.support.log;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

/**
 * 특정 logger의 이벤트를 모은다. 서버 스레드가 쓰고 테스트 스레드가 읽으므로 thread-safe 목록을 쓰고, MDC는 기록 시점에 고정한다 — 나중에 읽으면 테스트
 * 스레드의 MDC가 보인다.
 */
public final class CapturingAppender extends AppenderBase<ILoggingEvent> {

  private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
  private final Logger logger;

  private CapturingAppender(Logger logger) {
    this.logger = logger;
  }

  public static CapturingAppender attachTo(Class<?> loggerOwner) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerOwner);
    CapturingAppender appender = new CapturingAppender(logger);
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  public void detach() {
    logger.detachAppender(this);
    stop();
  }

  public List<ILoggingEvent> eventsWithMdc(String key, String value) {
    return events.stream().filter(e -> value.equals(e.getMDCPropertyMap().get(key))).toList();
  }

  public List<ILoggingEvent> events() {
    return List.copyOf(events);
  }

  @Override
  protected void append(ILoggingEvent event) {
    event.prepareForDeferredProcessing();
    events.add(event);
  }
}
