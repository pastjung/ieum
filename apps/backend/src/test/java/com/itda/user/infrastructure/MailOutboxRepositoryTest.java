package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.user.application.MailDispatchPolicy;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.MailDispatchFailureKinds;
import com.itda.user.domain.MailDispatchStatus;
import com.itda.user.domain.MailOutboxClaim;
import com.itda.user.domain.MailOutboxEntry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * {@code mail_outbox}의 조건부 선점·재시도·종결·정리를 실제 DB에 대고 확인한다.
 *
 * <p><b>이 테스트는 H2에서 돈다.</b> 따라서 MariaDB의 {@code DATETIME(6)} 저장 정밀도와 실제 동시 UPDATE 경쟁은 여기서 증명되지 않는다.
 * 여기서 고정하는 것은 조건 절의 논리다 — 어떤 조건이 0행을 만들고 어떤 조건이 1행을 만드는가. 실제 MariaDB 검증은 테스트 harness 방식을 정한 뒤 별도로
 * 추가한다.
 */
@DataJpaTest
@Import(MailOutboxRepositoryTest.RepositoryUnderTest.class)
class MailOutboxRepositoryTest {

  private static final String EMAIL = "learner@example.com";
  private static final int MAX_ATTEMPTS = 6;
  private static final Duration RETENTION = Duration.ofDays(7);

  @TestConfiguration(proxyBeanMethods = false)
  static class RepositoryUnderTest {

    /** 설정 바인딩은 이 테스트의 관심이 아니다. 계약 기본값을 그대로 담은 record를 직접 만든다. */
    @Bean
    MailDispatchPolicy mailDispatchPolicy() {
      return new MailDispatchProperties(
          MAX_ATTEMPTS,
          List.of(Duration.ofSeconds(30), Duration.ofMinutes(1)),
          Duration.ofMinutes(10),
          0.2,
          Duration.ofSeconds(60),
          Duration.ofMinutes(5),
          RETENTION);
    }

    @Bean
    MailOutboxRepositoryImpl mailOutboxRepository(
        MailOutboxJpaRepository jpaRepository, MailDispatchPolicy policy) {
      return new MailOutboxRepositoryImpl(jpaRepository, policy);
    }
  }

  @Autowired private MailOutboxRepositoryImpl repository;
  @Autowired private MailOutboxJpaRepository jpaRepository;

  private LocalDateTime now;

  @BeforeEach
  void fixTime() {
    this.now = MailOutboxEntry.truncate(LocalDateTime.of(2026, 9, 13, 9, 0));
  }

  private MailOutboxEntry pending(AccountActionPurpose purpose, Duration deadlineAfter) {
    return this.repository.save(
        MailOutboxEntry.enqueue(purpose, EMAIL, this.now.plus(deadlineAfter), this.now));
  }

  /** worker가 후보를 읽고 만드는 것과 같은 선점 요청. */
  private MailOutboxClaim claimOf(MailOutboxEntry entry, Duration visibility) {
    return new MailOutboxClaim(
        entry.getId(),
        entry.getPurpose(),
        entry.getRecipientEmail(),
        entry.getAttemptCount() + 1,
        entry.getNextAttemptAt(),
        MailOutboxEntry.truncate(this.now.plus(visibility)),
        entry.getDeliveryDeadlineAt());
  }

  private MailOutboxClaim claimOf(MailOutboxEntry entry) {
    return claimOf(entry, Duration.ofMinutes(5));
  }

  private MailOutboxEntry enqueuedAt(LocalDateTime enqueuedAt) {
    return this.repository.save(
        MailOutboxEntry.enqueue(
            AccountActionPurpose.EMAIL_VERIFICATION, EMAIL, enqueuedAt.plusHours(24), enqueuedAt));
  }

  private MailOutboxEntry reload(Long id) {
    return this.jpaRepository.findById(id).orElseThrow();
  }

  @Test
  void 접수한_작업은_PENDING이고_즉시_처리_대상이다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));

    assertThat(entry.getId()).isNotNull();
    assertThat(entry.getStatus()).isEqualTo(MailDispatchStatus.PENDING);
    assertThat(entry.getAttemptCount()).isZero();
    assertThat(entry.getNextAttemptAt()).isEqualTo(this.now);
    assertThat(entry.getCompletedAt()).isNull();
    assertThat(this.repository.findClaimCandidates(this.now, 10)).hasSize(1);
  }

  @Test
  void 선점하면_시도_횟수가_오르고_가시성_만료로_밀린다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    MailOutboxClaim claim = claimOf(entry);

    assertThat(this.repository.claim(claim, this.now, MAX_ATTEMPTS)).isTrue();

    MailOutboxEntry claimed = reload(entry.getId());
    assertThat(claimed.getAttemptCount()).isEqualTo(1);
    assertThat(claimed.getNextAttemptAt()).isEqualTo(claim.visibilityExpiresAt());
    assertThat(claimed.getStatus()).isEqualTo(MailDispatchStatus.PENDING);
  }

  @Test
  void 선점된_작업은_가시성이_지나기_전까지_후보가_아니다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    this.repository.claim(claimOf(entry), this.now, MAX_ATTEMPTS);

    assertThat(this.repository.findClaimCandidates(this.now.plusMinutes(1), 10)).isEmpty();
    assertThat(this.repository.findClaimCandidates(this.now.plusMinutes(5), 10)).hasSize(1);
  }

  @Test
  void 같은_후보로_두_번_선점할_수_없다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    MailOutboxClaim claim = claimOf(entry);
    assertThat(this.repository.claim(claim, this.now, MAX_ATTEMPTS)).isTrue();

    // 같은 후보를 들고 뒤늦게 들어온 worker는 attemptCount·nextAttemptAt 조건이 깨져 0행이다.
    assertThat(this.repository.claim(claim, this.now, MAX_ATTEMPTS)).isFalse();
    assertThat(reload(entry.getId()).getAttemptCount()).isEqualTo(1);
  }

  @Test
  void 기한이_지난_작업은_선점_후보가_아니다() {
    pending(AccountActionPurpose.PASSWORD_RESET, Duration.ofMinutes(30));

    assertThat(this.repository.findClaimCandidates(this.now.plusMinutes(30), 10)).isEmpty();
  }

  @Test
  void 기한이_지났으면_선점_자체가_실패한다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.PASSWORD_RESET, Duration.ofMinutes(30));

    assertThat(this.repository.claim(claimOf(entry), this.now.plusMinutes(30), MAX_ATTEMPTS))
        .isFalse();
  }

  @Test
  void 재시도는_PENDING을_유지하고_수신_주소를_남긴다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    MailOutboxClaim claim = claimOf(entry);
    this.repository.claim(claim, this.now, MAX_ATTEMPTS);
    LocalDateTime nextAttemptAt = MailOutboxEntry.truncate(this.now.plusSeconds(30));

    assertThat(
            this.repository.markRetry(
                claim, nextAttemptAt, MailDispatchFailureKinds.PROVIDER_RETRYABLE, this.now))
        .isTrue();

    MailOutboxEntry retried = reload(entry.getId());
    assertThat(retried.getStatus()).isEqualTo(MailDispatchStatus.PENDING);
    assertThat(retried.getNextAttemptAt()).isEqualTo(nextAttemptAt);
    assertThat(retried.getRecipientEmail()).isEqualTo(EMAIL);
    assertThat(retried.getCompletedAt()).isNull();
    assertThat(retried.getLastErrorKind()).isEqualTo(MailDispatchFailureKinds.PROVIDER_RETRYABLE);
  }

  @Test
  void 다음_재시도는_이전_실패_분류를_최신_원인으로_덮어쓴다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    MailOutboxClaim first = claimOf(entry);
    this.repository.claim(first, this.now, MAX_ATTEMPTS);
    this.repository.markRetry(
        first,
        MailOutboxEntry.truncate(this.now.plusSeconds(30)),
        MailDispatchFailureKinds.PROVIDER_AUTH_CONFIG_ERROR,
        this.now);

    MailOutboxEntry reclaimed = reload(entry.getId());
    MailOutboxClaim second = claimOf(reclaimed, Duration.ofMinutes(10));
    this.repository.claim(second, this.now.plusMinutes(1), MAX_ATTEMPTS);
    this.repository.markRetry(
        second,
        MailOutboxEntry.truncate(this.now.plusMinutes(2)),
        MailDispatchFailureKinds.PROVIDER_UNCERTAIN,
        this.now.plusMinutes(1));

    // 오래된 분류가 남아 있으면 운영자가 현재 원인을 잘못 읽는다.
    assertThat(reload(entry.getId()).getLastErrorKind())
        .isEqualTo(MailDispatchFailureKinds.PROVIDER_UNCERTAIN);
  }

  @Test
  void 종결은_수신_주소와_다음_시각을_지우고_종결_시각을_남긴다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    MailOutboxClaim claim = claimOf(entry);
    this.repository.claim(claim, this.now, MAX_ATTEMPTS);

    assertThat(
            this.repository.markTerminal(
                claim, MailDispatchStatus.PROVIDER_ACCEPTED, null, this.now))
        .isTrue();

    MailOutboxEntry terminal = reload(entry.getId());
    assertThat(terminal.getStatus()).isEqualTo(MailDispatchStatus.PROVIDER_ACCEPTED);
    assertThat(terminal.getRecipientEmail()).isNull();
    assertThat(terminal.getNextAttemptAt()).isNull();
    assertThat(terminal.getCompletedAt()).isEqualTo(this.now);
    assertThat(terminal.getLastErrorKind()).isNull();
  }

  @Test
  void 성공_종결에는_실패_분류를_남기지_않는다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    MailOutboxClaim claim = claimOf(entry);
    this.repository.claim(claim, this.now, MAX_ATTEMPTS);

    this.repository.markTerminal(
        claim,
        MailDispatchStatus.PROVIDER_ACCEPTED,
        MailDispatchFailureKinds.PROVIDER_REJECTED,
        this.now);

    assertThat(reload(entry.getId()).getLastErrorKind()).isNull();
  }

  @Test
  void 실패_종결에는_실패_분류를_남긴다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    MailOutboxClaim claim = claimOf(entry);
    this.repository.claim(claim, this.now, MAX_ATTEMPTS);

    this.repository.markTerminal(
        claim, MailDispatchStatus.FAILED, MailDispatchFailureKinds.PROVIDER_REJECTED, this.now);

    MailOutboxEntry failed = reload(entry.getId());
    assertThat(failed.getStatus()).isEqualTo(MailDispatchStatus.FAILED);
    assertThat(failed.getLastErrorKind()).isEqualTo(MailDispatchFailureKinds.PROVIDER_REJECTED);
  }

  @Test
  void 늦은_worker의_갱신은_다시_선점된_작업을_덮어쓰지_못한다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    MailOutboxClaim first = claimOf(entry);
    this.repository.claim(first, this.now, MAX_ATTEMPTS);

    // 가시성이 지나 다른 worker가 같은 작업을 다시 선점했다.
    MailOutboxEntry reclaimed = reload(entry.getId());
    MailOutboxClaim second =
        new MailOutboxClaim(
            reclaimed.getId(),
            reclaimed.getPurpose(),
            reclaimed.getRecipientEmail(),
            reclaimed.getAttemptCount() + 1,
            reclaimed.getNextAttemptAt(),
            MailOutboxEntry.truncate(this.now.plusMinutes(15)),
            reclaimed.getDeliveryDeadlineAt());
    assertThat(this.repository.claim(second, this.now.plusMinutes(10), MAX_ATTEMPTS)).isTrue();

    // 뒤늦게 돌아온 앞선 worker의 종결·재시도는 모두 0행으로 실패한다.
    assertThat(
            this.repository.markTerminal(
                first,
                MailDispatchStatus.FAILED,
                MailDispatchFailureKinds.PROVIDER_REJECTED,
                this.now))
        .isFalse();
    assertThat(
            this.repository.markRetry(
                first,
                this.now.plusHours(1),
                MailDispatchFailureKinds.PROVIDER_UNCERTAIN,
                this.now))
        .isFalse();

    MailOutboxEntry current = reload(entry.getId());
    assertThat(current.getStatus()).isEqualTo(MailDispatchStatus.PENDING);
    assertThat(current.getAttemptCount()).isEqualTo(2);
    assertThat(current.getNextAttemptAt()).isEqualTo(second.visibilityExpiresAt());
  }

  @Test
  void 기한이_지난_작업은_기한_초과로_정리된다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.PASSWORD_RESET, Duration.ofMinutes(30));

    assertThat(this.repository.failStale(this.now.plusHours(1), MAX_ATTEMPTS)).isEqualTo(1);

    MailOutboxEntry stale = reload(entry.getId());
    assertThat(stale.getStatus()).isEqualTo(MailDispatchStatus.FAILED);
    assertThat(stale.getLastErrorKind())
        .isEqualTo(MailDispatchFailureKinds.DELIVERY_DEADLINE_EXCEEDED);
    assertThat(stale.getRecipientEmail()).isNull();
    assertThat(stale.getCompletedAt()).isNotNull();
  }

  /** 상한까지 선점만 반복해, 마지막 시도 도중 worker가 중단된 상태를 만든다. */
  private void claimUntilMaxAttempts(MailOutboxEntry entry, Duration step) {
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      MailOutboxEntry candidate = reload(entry.getId());
      MailOutboxClaim claim =
          new MailOutboxClaim(
              candidate.getId(),
              candidate.getPurpose(),
              candidate.getRecipientEmail(),
              candidate.getAttemptCount() + 1,
              candidate.getNextAttemptAt(),
              MailOutboxEntry.truncate(this.now.plus(step.multipliedBy(attempt + 1L))),
              candidate.getDeliveryDeadlineAt());
      assertThat(
              this.repository.claim(claim, this.now.plus(step.multipliedBy(attempt)), MAX_ATTEMPTS))
          .isTrue();
    }
  }

  @Test
  void 시도_상한에_닿은_채_중단된_작업은_시도_초과로_정리된다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofDays(1));
    claimUntilMaxAttempts(entry, Duration.ofMinutes(5));

    assertThat(this.repository.failStale(this.now.plusHours(1), MAX_ATTEMPTS)).isEqualTo(1);

    MailOutboxEntry stale = reload(entry.getId());
    assertThat(stale.getStatus()).isEqualTo(MailDispatchStatus.FAILED);
    assertThat(stale.getLastErrorKind()).isEqualTo(MailDispatchFailureKinds.MAX_ATTEMPTS_EXCEEDED);
  }

  @Test
  void 기한과_시도_상한에_모두_걸리면_기한_초과로_기록한다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.PASSWORD_RESET, Duration.ofMinutes(30));
    claimUntilMaxAttempts(entry, Duration.ofMinutes(1));

    assertThat(this.repository.failStale(this.now.plusHours(1), MAX_ATTEMPTS)).isEqualTo(1);

    assertThat(reload(entry.getId()).getLastErrorKind())
        .isEqualTo(MailDispatchFailureKinds.DELIVERY_DEADLINE_EXCEEDED);
  }

  @Test
  void 가시성_안에_있는_작업은_정리_대상이_아니다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    this.repository.claim(claimOf(entry), this.now, MAX_ATTEMPTS);

    assertThat(this.repository.failStale(this.now.plusMinutes(1), MAX_ATTEMPTS)).isZero();
    assertThat(reload(entry.getId()).getStatus()).isEqualTo(MailDispatchStatus.PENDING);
  }

  @Test
  void 보존_기간이_지난_종결_행만_지운다() {
    MailOutboxEntry terminal =
        pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    MailOutboxClaim claim = claimOf(terminal);
    this.repository.claim(claim, this.now, MAX_ATTEMPTS);
    this.repository.markTerminal(claim, MailDispatchStatus.SKIPPED, null, this.now);
    MailOutboxEntry stillPending =
        pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));

    // 호출자는 "정리 실행 시각 - 보존 기간"을 기준으로 넘긴다. 종결 7일 하루 뒤에 정리한 상황이다.
    LocalDateTime threshold = this.now.plusDays(8).minus(RETENTION);
    assertThat(this.repository.deleteTerminalCompletedBefore(threshold)).isEqualTo(1);

    assertThat(this.jpaRepository.findById(terminal.getId())).isEmpty();
    assertThat(this.jpaRepository.findById(stillPending.getId())).isPresent();
  }

  @Test
  void 보존_기간_안의_종결_행은_남긴다() {
    MailOutboxEntry terminal =
        pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    MailOutboxClaim claim = claimOf(terminal);
    this.repository.claim(claim, this.now, MAX_ATTEMPTS);
    this.repository.markTerminal(claim, MailDispatchStatus.PROVIDER_ACCEPTED, null, this.now);

    // 종결 6일 뒤에 정리하면 아직 보존 기간 안이다.
    LocalDateTime threshold = this.now.plusDays(6).minus(RETENTION);
    assertThat(this.repository.deleteTerminalCompletedBefore(threshold)).isZero();
    assertThat(this.jpaRepository.findById(terminal.getId())).isPresent();
  }

  @Test
  void 종결_상태가_아니면_종결할_수_없다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));

    assertThatThrownBy(
            () ->
                this.repository.markTerminal(
                    claimOf(entry), MailDispatchStatus.PENDING, null, this.now))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 후보_조회는_batch_크기를_넘기지_않고_오래_기다린_것부터_준다() {
    // 접수 시각이 다르면 nextAttemptAt도 달라진다. 가장 오래 기다린 두 건만 나와야 한다.
    MailOutboxEntry oldest = enqueuedAt(this.now);
    MailOutboxEntry middle = enqueuedAt(this.now.plusMinutes(1));
    MailOutboxEntry newest = enqueuedAt(this.now.plusMinutes(2));

    List<MailOutboxEntry> candidates =
        this.repository.findClaimCandidates(this.now.plusMinutes(40), 2);

    assertThat(candidates)
        .extracting(MailOutboxEntry::getId)
        .containsExactly(oldest.getId(), middle.getId())
        .doesNotContain(newest.getId());
  }
}
