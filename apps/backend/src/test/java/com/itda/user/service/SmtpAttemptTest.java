package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.mail.MessagingException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 시간 초과와 SMTP 결과의 두 선후관계를 시간 경쟁 없이 고정한다. 실제 경로는 SMTP/DNS 테스트가 검증한다. */
class SmtpAttemptTest {
  private static SmtpMailProvider.Attempt attempt() {
    return new SmtpMailProvider.Attempt(System.nanoTime(), TimeUnit.MINUTES.toNanos(1));
  }

  @Test
  void 본문_이전_취소는_RETRYABLE이며_이후_단계로_진행하지_못한다() throws Exception {
    SmtpMailProvider.Attempt attempt = attempt();
    attempt.advance(SmtpMailProvider.Stage.ENVELOPE);
    assertThat(attempt.abort(SmtpMailProvider.AbortReason.TIMEOUT).result())
        .isEqualTo(MailProvider.SendResult.RETRYABLE);
    assertThatThrownBy(() -> attempt.advance(SmtpMailProvider.Stage.DATA))
        .isInstanceOf(MessagingException.class);
  }

  @Test
  void 본문_전송_뒤_시간_초과가_먼저면_늦은_수락으로_결과를_덮지_않는다() throws Exception {
    SmtpMailProvider.Attempt attempt = attempt();
    attempt.advance(SmtpMailProvider.Stage.DATA);
    assertThat(attempt.abort(SmtpMailProvider.AbortReason.TIMEOUT).result())
        .isEqualTo(MailProvider.SendResult.UNCERTAIN);
    assertThat(attempt.complete(accepted()).result()).isEqualTo(MailProvider.SendResult.UNCERTAIN);
  }

  @Test
  void 명시적_수락_확인이_먼저면_종료_중_시간_초과로_결과를_바꾸지_않는다() throws Exception {
    SmtpMailProvider.Attempt attempt = attempt();
    attempt.advance(SmtpMailProvider.Stage.DATA);
    assertThat(attempt.complete(accepted()).result()).isEqualTo(MailProvider.SendResult.ACCEPTED);
    assertThat(attempt.abort(SmtpMailProvider.AbortReason.TIMEOUT).result())
        .isEqualTo(MailProvider.SendResult.ACCEPTED);
  }

  @Test
  void 감시가_늦어도_이미_만료된_예산으로_연결을_시작하지_않는다() {
    SmtpMailProvider.Attempt attempt =
        new SmtpMailProvider.Attempt(
            System.nanoTime() - TimeUnit.SECONDS.toNanos(2), TimeUnit.SECONDS.toNanos(1));
    assertThatThrownBy(() -> attempt.advance(SmtpMailProvider.Stage.CONNECT))
        .isInstanceOf(MessagingException.class);
    assertThat(attempt.abort(SmtpMailProvider.AbortReason.TIMEOUT).result())
        .isEqualTo(MailProvider.SendResult.RETRYABLE);
  }

  private static SmtpMailProvider.Classification accepted() {
    return new SmtpMailProvider.Classification(
        MailProvider.SendResult.ACCEPTED, SmtpMailProvider.Reason.ACCEPTED, null);
  }
}
