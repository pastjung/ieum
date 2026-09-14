package com.itda.user.repository;

import com.itda.user.domain.MailDispatchFailureKinds;
import com.itda.user.domain.MailDispatchStatus;
import com.itda.user.domain.MailOutboxClaim;
import com.itda.user.domain.MailOutboxEntry;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link MailOutboxRepository}의 JPA 어댑터.
 *
 * <p>{@code save}에 {@code REQUIRES_NEW}를 붙이지 않는다. 회원가입은 {@code users} INSERT와 메일 작업 등록을 <b>같은
 * 트랜잭션</b>으로 커밋해야 하므로(API 명세 3.2절), 호출자가 이미 연 트랜잭션에 참여해야 한다. 새 트랜잭션을 강제하면 한쪽만 커밋되는 상태를 만든다.
 *
 * <p>선점·재시도·종결·정리는 각각 자기 트랜잭션이 필요하다. worker의 한 단계가 실패해도 앞 단계의 상태 전이는 남아 있어야 하기 때문이다.
 *
 * <p>모든 시각은 마이크로초로 절삭해 넘긴다. 조건부 갱신이 {@code DATETIME(6)} 값의 정확한 일치에 의존한다 (ERD 3.5절).
 */
@Repository
public class MailOutboxRepositoryImpl implements MailOutboxRepository {

  private final MailOutboxJpaRepository jpaRepository;

  public MailOutboxRepositoryImpl(MailOutboxJpaRepository jpaRepository) {
    this.jpaRepository = jpaRepository;
  }

  @Override
  public MailOutboxEntry save(MailOutboxEntry entry) {
    return this.jpaRepository.save(entry);
  }

  @Override
  @Transactional(readOnly = true)
  public List<MailOutboxEntry> findClaimCandidates(LocalDateTime now, int maxAttempts, int limit) {
    return this.jpaRepository.findClaimCandidates(
        MailOutboxEntry.truncate(now), maxAttempts, Limit.of(limit));
  }

  @Override
  @Transactional
  public boolean claim(MailOutboxClaim claim, LocalDateTime now, int maxAttempts) {
    return this.jpaRepository.claim(
            claim.id(),
            claim.attemptCount() - 1,
            MailOutboxEntry.truncate(claim.previousNextAttemptAt()),
            MailOutboxEntry.truncate(claim.visibilityExpiresAt()),
            MailOutboxEntry.truncate(now),
            maxAttempts)
        == 1;
  }

  @Override
  @Transactional
  public boolean markRetry(
      MailOutboxClaim claim, LocalDateTime nextAttemptAt, String lastErrorKind, LocalDateTime now) {
    return this.jpaRepository.markRetry(
            claim.id(),
            claim.attemptCount(),
            MailOutboxEntry.truncate(claim.visibilityExpiresAt()),
            MailOutboxEntry.truncate(nextAttemptAt),
            lastErrorKind,
            MailOutboxEntry.truncate(now))
        == 1;
  }

  @Override
  @Transactional
  public boolean markTerminal(
      MailOutboxClaim claim, MailDispatchStatus status, String lastErrorKind, LocalDateTime now) {
    if (!status.isTerminal()) {
      throw new IllegalArgumentException("종결 상태가 아닙니다.");
    }
    return this.jpaRepository.markTerminal(
            claim.id(),
            claim.attemptCount(),
            MailOutboxEntry.truncate(claim.visibilityExpiresAt()),
            status,
            // PROVIDER_ACCEPTED와 SKIPPED에서는 실패 분류를 정리한다 (ERD 3.5절).
            status == MailDispatchStatus.FAILED ? lastErrorKind : null,
            MailOutboxEntry.truncate(now))
        == 1;
  }

  /**
   * 기한 초과를 먼저 종결하고 그다음 시도 초과를 종결한다. 두 조건이 동시에 해당하면 기한 초과를 우선 기록하라는 계약(ERD 3.5절 판정 순서)을 실행 순서로 지킨다.
   */
  @Override
  @Transactional
  public int failStale(LocalDateTime now, int maxAttempts) {
    LocalDateTime truncated = MailOutboxEntry.truncate(now);
    int deadlineExceeded =
        this.jpaRepository.failDeadlineExceeded(
            truncated, MailDispatchFailureKinds.DELIVERY_DEADLINE_EXCEEDED);
    int maxAttemptsExceeded =
        this.jpaRepository.failMaxAttemptsExceeded(
            truncated, maxAttempts, MailDispatchFailureKinds.MAX_ATTEMPTS_EXCEEDED);
    return deadlineExceeded + maxAttemptsExceeded;
  }

  @Override
  @Transactional
  public int deleteTerminalCompletedBefore(LocalDateTime threshold) {
    return this.jpaRepository.deleteTerminalCompletedBefore(MailOutboxEntry.truncate(threshold));
  }
}
