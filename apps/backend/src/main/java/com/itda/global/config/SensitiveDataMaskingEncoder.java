package com.itda.global.config;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.Layout;
import com.itda.global.web.RequestTracingFilter;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * pattern으로 만든 로그 한 건을 {@link SensitiveLogMasker}로 가린 뒤 내보낸다. {@code logback-spring.xml}의 콘솔
 * appender가 쓴다.
 *
 * <p>message converter가 아니라 encoder에서 가리는 이유: pattern이 조합한 최종 문자열에는 message뿐 아니라 예외 message·stack
 * trace·원인 체인이 함께 들어 있다. 이 경계 하나에서 처리하면 어느 converter가 값을 찍었는지와 무관하게 같은 규칙이 적용된다.
 *
 * <p>가리는 대상은 {@link SensitiveLogMasker}에 규칙으로 등록된 형식(JDBC 접속 정보, UNIQUE 위반 값 등)뿐이며, 등록되지 않은 새 민감정보
 * 형식을 자동으로 찾아내지는 않는다. layout이 준비되지 않았거나 포맷팅·마스킹 중 예외가 발생하거나 결과가 null이면 원문 대신
 * level·logger·requestId와 고정 문구만 남겨, 로그가 있었다는 사실은 잃지 않는다.
 */
public class SensitiveDataMaskingEncoder extends PatternLayoutEncoder {

  static final String WITHHELD = "[log message withheld: sensitive data masking failed]";

  private final UnaryOperator<String> masker;

  public SensitiveDataMaskingEncoder() {
    this(SensitiveLogMasker::mask);
  }

  SensitiveDataMaskingEncoder(UnaryOperator<String> masker) {
    this.masker = masker;
  }

  @Override
  public byte[] encode(ILoggingEvent event) {
    return toBytes(redact(event));
  }

  /** layout 조회부터 마스킹까지 한 경계 안에서 처리한다. 포맷팅에서 난 실패도 원문 대신 고정 문구로 끝나야 한다. */
  private String redact(ILoggingEvent event) {
    try {
      Layout<ILoggingEvent> layout = getLayout();
      if (layout == null) {
        return withheld(event);
      }
      String masked = this.masker.apply(layout.doLayout(event));
      return masked != null ? masked : withheld(event);
    } catch (RuntimeException | StackOverflowError e) {
      // 실패 원인도 원문을 담고 있을 수 있어 남기지 않는다.
      return withheld(event);
    }
  }

  private static String withheld(ILoggingEvent event) {
    String requestId = requestIdOf(event);
    return event.getLevel()
        + " "
        + event.getLoggerName()
        + (requestId == null ? "" : " [" + requestId + "]")
        + " "
        + WITHHELD
        + CoreConstants.LINE_SEPARATOR;
  }

  /** MDC를 읽을 수 없는 event에서도 고정 문구는 나가야 하므로 requestId는 읽히는 경우에만 붙인다. */
  private static String requestIdOf(ILoggingEvent event) {
    try {
      Map<String, String> mdc = event.getMDCPropertyMap();
      return mdc == null ? null : mdc.get(RequestTracingFilter.MDC_KEY);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private byte[] toBytes(String text) {
    Charset charset = getCharset();
    return charset == null ? text.getBytes(Charset.defaultCharset()) : text.getBytes(charset);
  }
}
