package com.itda.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.MailDispatchStatus;
import com.itda.user.domain.MailOutboxClaim;
import com.itda.user.domain.MailOutboxEntry;
import com.itda.user.domain.MailOutboxRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 접수 경계에서 수신 주소를 정규화·검증하는지 고정한다.
 *
 * <p>worker는 수신 주소를 {@code Email.of}로 다시 읽어야 진행한다. 형식이 어긋난 주소가 저장되면 선점할 때마다 예외로 끝나 상태 전이도 실패 분류도
 * 남기지 못한 채 시도 횟수만 상한까지 태운다. 그래서 잘못된 주소는 {@code save} 이전에 끝나야 한다.
 */
class MailOutboxEnqueueServiceTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 13, 9, 0);

  private final RecordingRepository repository = new RecordingRepository();
  private final MailOutboxEnqueueService service = new MailOutboxEnqueueService(this.repository);

  @Test
  void 앞뒤_공백과_대문자를_정규화해_저장한다() {
    MailOutboxEntry saved =
        this.service.enqueue(
            AccountActionPurpose.EMAIL_VERIFICATION, "  Learner@Example.COM \t", NOW);

    assertThat(saved.getRecipientEmail()).isEqualTo("learner@example.com");
    assertThat(this.repository.saved).hasSize(1);
  }

  @Test
  void 용도별_발송_기한을_적용한다() {
    this.service.enqueue(AccountActionPurpose.EMAIL_VERIFICATION, "learner@example.com", NOW);
    this.service.enqueue(AccountActionPurpose.PASSWORD_RESET, "learner@example.com", NOW);

    assertThat(this.repository.saved.get(0).getDeliveryDeadlineAt()).isEqualTo(NOW.plusHours(24));
    assertThat(this.repository.saved.get(1).getDeliveryDeadlineAt()).isEqualTo(NOW.plusMinutes(30));
    assertThat(this.repository.saved)
        .allSatisfy(entry -> assertThat(entry.getStatus()).isEqualTo(MailDispatchStatus.PENDING));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "learner@example",
        "learner",
        "@example.com",
        "learner@@example.com",
        "learner example@example.com",
        "   ",
        ""
      })
  void 형식이_어긋난_주소는_저장하지_않는다(String rawEmail) {
    assertThatThrownBy(
            () -> this.service.enqueue(AccountActionPurpose.EMAIL_VERIFICATION, rawEmail, NOW))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(this.repository.saved).isEmpty();
  }

  @Test
  void 주소가_없으면_저장하지_않는다() {
    assertThatThrownBy(() -> this.service.enqueue(AccountActionPurpose.PASSWORD_RESET, null, NOW))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(this.repository.saved).isEmpty();
  }

  @Test
  void 허용_길이를_넘는_주소는_저장하지_않는다() {
    // local part 244자 + "@example.com"(12자) = 256자 > 254자.
    String tooLong = "a".repeat(244) + "@example.com";

    assertThatThrownBy(
            () -> this.service.enqueue(AccountActionPurpose.EMAIL_VERIFICATION, tooLong, NOW))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(this.repository.saved).isEmpty();
  }

  @Test
  void 거부_예외에_입력한_주소를_담지_않는다() {
    String rawEmail = "Learner.Secret@example";

    assertThatThrownBy(
            () -> this.service.enqueue(AccountActionPurpose.EMAIL_VERIFICATION, rawEmail, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        // 이 예외는 로그·모니터링·상위 예외 message로 흘러갈 수 있다.
        .hasMessageNotContaining(rawEmail)
        .hasMessageNotContaining("Learner.Secret")
        .hasMessageNotContaining("example");
  }

  /** 저장 호출 여부까지 봐야 "Redis·DB에 닿기 전에 막혔는가"를 확인할 수 있다. */
  private static final class RecordingRepository implements MailOutboxRepository {

    private final List<MailOutboxEntry> saved = new ArrayList<>();

    @Override
    public MailOutboxEntry save(MailOutboxEntry entry) {
      this.saved.add(entry);
      return entry;
    }

    @Override
    public List<MailOutboxEntry> findClaimCandidates(LocalDateTime now, int limit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean claim(MailOutboxClaim claim, LocalDateTime now, int maxAttempts) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean markRetry(
        MailOutboxClaim claim,
        LocalDateTime nextAttemptAt,
        String lastErrorKind,
        LocalDateTime now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean markTerminal(
        MailOutboxClaim claim, MailDispatchStatus status, String lastErrorKind, LocalDateTime now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int failStale(LocalDateTime now, int maxAttempts) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int deleteTerminalCompletedBefore(LocalDateTime threshold) {
      throw new UnsupportedOperationException();
    }
  }
}
