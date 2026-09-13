package com.itda.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.MailOutboxEntry;
import com.itda.user.domain.MailOutboxRepository;
import com.itda.user.domain.UserRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 이메일 인증 요청의 판정을 저장소 없이 고정한다 (API 명세 3.4.1절).
 *
 * <p>여기서 확인하는 것은 "무엇을 422로 거부하고, 무엇을 접수하며, 무엇을 계정 단서로 흘리지 않는가"다.
 */
class EmailVerificationRequestServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
  private static final String EMAIL = "learner@example.com";

  private RecordingMailOutboxRepository outbox;
  private RecordingTransactionOperations transaction;
  private EmailVerificationRequestService service;

  @BeforeEach
  void setUp() {
    this.outbox = new RecordingMailOutboxRepository();
    this.transaction = new RecordingTransactionOperations();
    this.service =
        new EmailVerificationRequestService(
            new MailOutboxEnqueueService(this.outbox),
            this.transaction,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private ErrorCode errorCodeOf(String rawEmail) {
    return catchThrowableOfType(BusinessException.class, () -> this.service.request(rawEmail))
        .getErrorCode();
  }

  // ---------- 계정 열거 방지 ----------

  @Test
  void 계정을_조회할_수단_자체를_갖지_않는다() {
    // 계정 상태로 동기 경로가 갈라지지 않는다는 성질을 구조로 보장한다. spy보다 강한 보장이다.
    assertThat(Arrays.stream(EmailVerificationRequestService.class.getDeclaredFields()))
        .extracting(Field::getType)
        .doesNotContain(UserRepository.class);
  }

  // ---------- 접수 ----------

  @Test
  void 형식이_유효하면_EMAIL_VERIFICATION_작업을_접수한다() {
    this.service.request(EMAIL);

    assertThat(this.outbox.saved).singleElement();
    MailOutboxEntry entry = this.outbox.saved.get(0);
    assertThat(entry.getPurpose()).isEqualTo(AccountActionPurpose.EMAIL_VERIFICATION);
    assertThat(entry.getRecipientEmail()).isEqualTo(EMAIL);
    assertThat(entry.getAttemptCount()).isZero();
  }

  @Test
  void 접수는_트랜잭션_안에서_한다() {
    this.service.request(EMAIL);

    assertThat(this.transaction.executions).isEqualTo(1);
    assertThat(this.transaction.savesInsideTransaction).isEqualTo(1);
  }

  @Test
  void 앞뒤_공백을_제거하고_소문자로_정규화한다() {
    this.service.request("  LEARNER@Example.COM  ");

    assertThat(this.outbox.saved.get(0).getRecipientEmail()).isEqualTo(EMAIL);
  }

  @Test
  void 같은_이메일로_여러_번_요청해도_그때마다_접수한다() {
    this.service.request(EMAIL);
    this.service.request(EMAIL);

    // 중복 억제는 worker의 발송 정책이지 동기 접수의 책임이 아니다.
    assertThat(this.outbox.saved).hasSize(2);
  }

  // ---------- 형식 위반 ----------

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "", // 빈 문자열
        "   ", // 공백만
        "\t\n", // 공백만 (다른 종류)
        "learner", // @ 없음
        "learner@", // 도메인 없음
        "@example.com", // local part 없음
        "learner@example", // TLD 없음
        "learner example@test.com" // 공백 포함
      })
  void 형식_위반은_422_INVALID_ACCOUNT_INPUT이다(String rawEmail) {
    assertThat(errorCodeOf(rawEmail)).isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);
    assertThat(this.outbox.saved).isEmpty();
    // 거부된 요청으로 트랜잭션을 열지 않는다.
    assertThat(this.transaction.executions).isZero();
  }

  @Test
  void 정규화_후_254자까지_허용하고_255자부터_거부한다() {
    String domain = "@example.com";
    String max = "a".repeat(254 - domain.length()) + domain;
    String over = "a".repeat(255 - domain.length()) + domain;

    assertThat(max).hasSize(254);
    assertThatCode(() -> this.service.request(max)).doesNotThrowAnyException();
    assertThat(errorCodeOf(over)).isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);
  }

  // ---------- 저장소 장애 ----------

  @Test
  void INSERT_실패는_503_AUTH_STATE_UNAVAILABLE이다() {
    this.transaction.willFail(new QueryTimeoutException("응답 없음"));

    assertThat(errorCodeOf(EMAIL)).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  void commit_실패도_503이다() {
    // execute가 반환되기 직전(= commit 자리)에서 터진다. 트랜잭션 안에서 잡았다면 여기서 202가 나가 버린다.
    this.transaction.willFailAfterAction(new TransactionSystemException("commit 실패"));

    assertThat(errorCodeOf(EMAIL)).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.transaction.savesInsideTransaction).isEqualTo(1);
  }

  @Test
  void commit_결과_불확실도_503이다() {
    this.transaction.willFailAfterAction(new TransactionSystemException("commit 적용 여부를 알 수 없음"));

    assertThat(errorCodeOf(EMAIL)).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  void 오류_message에_이메일이_없다() {
    // 형식은 틀렸지만 이메일 원문을 그대로 품은 값이다. message에 옮겨졌다면 여기서 드러난다.
    String rejected = EMAIL + "@";

    BusinessException thrown =
        catchThrowableOfType(BusinessException.class, () -> this.service.request(rejected));

    assertThat(thrown.getErrorCode().getMessage()).doesNotContain(EMAIL).doesNotContain("example");
    // 원인 예외를 붙이지 않아 원문이 예외 사슬로 흘러가지 않는다.
    assertThat(thrown.getCause()).isNull();
  }

  // ---------- 도우미 ----------

  private final class RecordingMailOutboxRepository implements MailOutboxRepository {

    private final List<MailOutboxEntry> saved = new ArrayList<>();

    @Override
    public MailOutboxEntry save(MailOutboxEntry entry) {
      EmailVerificationRequestServiceTest.this.transaction.countSave();
      this.saved.add(entry);
      return entry;
    }

    @Override
    public List<MailOutboxEntry> findClaimCandidates(LocalDateTime now, int limit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean claim(
        com.itda.user.domain.MailOutboxClaim claim, LocalDateTime now, int maxAttempts) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean markRetry(
        com.itda.user.domain.MailOutboxClaim claim,
        LocalDateTime nextAttemptAt,
        String failureKind,
        LocalDateTime now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean markTerminal(
        com.itda.user.domain.MailOutboxClaim claim,
        com.itda.user.domain.MailDispatchStatus status,
        String failureKind,
        LocalDateTime now) {
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

  /** 트랜잭션 경계를 흉내 낸다. {@code execute}가 반환되기 직전이 commit 자리다. */
  private static final class RecordingTransactionOperations implements TransactionOperations {

    private int executions;
    private int savesInsideTransaction;
    private RuntimeException failureBeforeAction;
    private RuntimeException failureAfterAction;

    void willFail(RuntimeException failure) {
      this.failureBeforeAction = failure;
    }

    void willFailAfterAction(RuntimeException failure) {
      this.failureAfterAction = failure;
    }

    void countSave() {
      this.savesInsideTransaction++;
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) {
      this.executions++;
      if (this.failureBeforeAction != null) {
        throw this.failureBeforeAction;
      }
      T result =
          action.doInTransaction(
              new org.springframework.transaction.support.SimpleTransactionStatus());
      if (this.failureAfterAction != null) {
        throw this.failureAfterAction;
      }
      return result;
    }
  }
}
