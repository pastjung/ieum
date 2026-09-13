package com.itda.support.mail;

import com.itda.user.application.port.out.MailMessage;
import com.itda.user.application.port.out.MailProvider;
import com.itda.user.application.port.out.MailSendOutcome;
import com.itda.user.application.port.out.MailSendResult;
import com.itda.user.infrastructure.CompactTokenHasher;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import org.slf4j.MDC;

/**
 * 테스트 전용 메일 제공자 stub.
 *
 * <p><b>test source에만 있다.</b> 운영 코드에 발송 성공을 가장하는 구현을 등록하지 않는다는 것이 이번 이슈의 결정이며, 실제 제공자 adapter는 후속
 * 이슈에서 만든다.
 *
 * <p>테스트가 다섯 결과를 명시적으로 고른다. 기본값을 두지 않고 매번 고르게 해서, 어떤 결과를 가정한 테스트인지 읽는 사람이 알 수 있게 한다.
 *
 * <p>전달받은 메시지를 기록하되 토큰 원문은 보관하지 않는다. 실패 message나 리포트로 새지 않게 하기 위해서다. 호출 시점의 MDC {@code requestId}는
 * 함께 남긴다 — 처리 시도마다 새 식별자가 붙는지 검증해야 하기 때문이다.
 */
public final class RecordingMailProvider implements MailProvider {

  private final List<Sent> sent = new ArrayList<>();

  /** 호출마다 하나씩 꺼내 쓴다. 비면 마지막 결과를 계속 돌려준다. */
  private final Deque<MailSendOutcome> queued = new ArrayDeque<>();

  private MailSendOutcome nextOutcome;

  /** 호출할 때마다 예외를 던지게 만드는 장치. {@code null}이면 정상 동작한다. */
  private Function<MailMessage, RuntimeException> failure;

  public RecordingMailProvider(MailSendResult firstResult) {
    this.nextOutcome = MailSendOutcome.of(firstResult);
  }

  public void willReturn(MailSendResult result) {
    this.nextOutcome = MailSendOutcome.of(result);
  }

  public void willReturn(MailSendResult result, Instant providerRetryAt) {
    this.nextOutcome = MailSendOutcome.retryAt(result, providerRetryAt);
  }

  /** 다음 한 번의 호출에만 적용할 결과를 예약한다. */
  public void willReturnOnce(MailSendResult result) {
    this.queued.addLast(MailSendOutcome.of(result));
  }

  public void willThrow(Function<MailMessage, RuntimeException> failure) {
    this.failure = failure;
  }

  @Override
  public MailSendOutcome send(MailMessage message) {
    this.sent.add(
        new Sent(
            message.purpose().name(),
            message.recipientEmail(),
            hasToken(message),
            CompactTokenHasher.hash(message.actionToken()),
            MDC.get("requestId")));
    if (this.failure != null) {
      // 함수가 null을 돌려주면 그 호출은 정상 처리한다. 한 batch에서 일부만 실패시키는 테스트를 위해서다.
      RuntimeException error = this.failure.apply(message);
      if (error != null) {
        throw error;
      }
    }
    return this.queued.isEmpty() ? this.nextOutcome : this.queued.pollFirst();
  }

  private static boolean hasToken(MailMessage message) {
    return message.actionToken() != null && !message.actionToken().isBlank();
  }

  public List<Sent> sent() {
    return List.copyOf(this.sent);
  }

  public int sendCount() {
    return this.sent.size();
  }

  /**
   * 토큰 원문을 남기지 않는다. 어떤 토큰이 전달됐는지 확인해야 하는 테스트를 위해 계약과 같은 규칙의 해시만 남긴다 — 저장소가 보관하는 값과 같은 형태라 원문을 되살릴 수
   * 없다.
   */
  public record Sent(
      String purpose,
      String recipientEmail,
      boolean hadToken,
      String tokenHash,
      String requestId) {}
}
