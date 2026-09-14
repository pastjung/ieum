package com.itda.user.repository;

import com.itda.user.domain.MailDispatchStatus;
import com.itda.user.domain.MailOutboxEntry;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code mail_outbox}의 Spring Data 접근점.
 *
 * <p>선점·재시도·종결은 파생 쿼리가 아니라 조건부 UPDATE로 쓴다. 읽고-판단하고-쓰는 사이에 다른 worker가 끼어들 수 있으므로, 조건을 UPDATE의 WHERE에
 * 그대로 실어야 갱신된 행 수로 경쟁 결과를 알 수 있다 (ERD 3.5절).
 */
public interface MailOutboxJpaRepository extends JpaRepository<MailOutboxEntry, Long> {

  /**
   * 선점 후보를 읽는다. 조회 자체는 상태를 바꾸지 않으며, 여기서 읽은 {@code attemptCount}·{@code nextAttemptAt}이 뒤따르는 선점
   * UPDATE의 조건이 된다.
   */
  @Query(
      """
      select e from MailOutboxEntry e
      where e.status = com.itda.user.domain.MailDispatchStatus.PENDING
        and e.nextAttemptAt <= :now
        and e.deliveryDeadlineAt > :now
        and e.attemptCount < :maxAttempts
      order by e.nextAttemptAt asc
      """)
  List<MailOutboxEntry> findClaimCandidates(
      @Param("now") LocalDateTime now, @Param("maxAttempts") int maxAttempts, Limit limit);

  /**
   * 조건부 UPDATE 한 번으로 선점한다. 갱신된 행이 없으면 다른 worker가 이미 가져갔거나 기한·시도 조건이 더 이상 맞지 않는다.
   *
   * <p>{@code attemptCount}를 1 증가시키고 {@code nextAttemptAt}을 가시성 만료 시각으로 민다. 이 두 값이 이후 갱신의 조건이 된다.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update MailOutboxEntry e
         set e.attemptCount = e.attemptCount + 1,
             e.nextAttemptAt = :visibilityExpiresAt,
             e.updatedAt = :now
       where e.id = :id
         and e.status = com.itda.user.domain.MailDispatchStatus.PENDING
         and e.attemptCount = :expectedAttemptCount
         and e.nextAttemptAt = :expectedNextAttemptAt
         and e.deliveryDeadlineAt > :now
         and e.attemptCount < :maxAttempts
      """)
  int claim(
      @Param("id") Long id,
      @Param("expectedAttemptCount") int expectedAttemptCount,
      @Param("expectedNextAttemptAt") LocalDateTime expectedNextAttemptAt,
      @Param("visibilityExpiresAt") LocalDateTime visibilityExpiresAt,
      @Param("now") LocalDateTime now,
      @Param("maxAttempts") int maxAttempts);

  /**
   * 재시도로 되돌린다. {@code PENDING}을 유지하고 수신 주소와 NULL인 {@code completedAt}을 그대로 둔다.
   *
   * <p>조건에 선점 때의 {@code attemptCount}와 가시성 만료 시각을 함께 건다. 가시성이 지나 다른 worker가 다시 선점했다면 두 값이 모두 바뀌어 이
   * 갱신은 0행으로 실패한다.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update MailOutboxEntry e
         set e.nextAttemptAt = :nextAttemptAt,
             e.lastErrorKind = :lastErrorKind,
             e.updatedAt = :now
       where e.id = :id
         and e.status = com.itda.user.domain.MailDispatchStatus.PENDING
         and e.attemptCount = :claimedAttemptCount
         and e.nextAttemptAt = :claimedVisibilityExpiresAt
      """)
  int markRetry(
      @Param("id") Long id,
      @Param("claimedAttemptCount") int claimedAttemptCount,
      @Param("claimedVisibilityExpiresAt") LocalDateTime claimedVisibilityExpiresAt,
      @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
      @Param("lastErrorKind") String lastErrorKind,
      @Param("now") LocalDateTime now);

  /** 종결한다. 수신 주소와 {@code nextAttemptAt}을 지우고 {@code completedAt}을 기록한다. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update MailOutboxEntry e
         set e.status = :status,
             e.recipientEmail = null,
             e.nextAttemptAt = null,
             e.completedAt = :now,
             e.lastErrorKind = :lastErrorKind,
             e.updatedAt = :now
       where e.id = :id
         and e.status = com.itda.user.domain.MailDispatchStatus.PENDING
         and e.attemptCount = :claimedAttemptCount
         and e.nextAttemptAt = :claimedVisibilityExpiresAt
      """)
  int markTerminal(
      @Param("id") Long id,
      @Param("claimedAttemptCount") int claimedAttemptCount,
      @Param("claimedVisibilityExpiresAt") LocalDateTime claimedVisibilityExpiresAt,
      @Param("status") MailDispatchStatus status,
      @Param("lastErrorKind") String lastErrorKind,
      @Param("now") LocalDateTime now);

  /**
   * 마지막 시도 도중 중단돼 스스로 종결하지 못한 작업을 {@code FAILED}로 정리한다.
   *
   * <p>기한 초과와 시도 초과가 동시에 해당하면 기한 초과를 우선 기록해야 하므로(ERD 3.5절 판정 순서) 두 조건을 한 UPDATE에 섞지 않고 호출자가 기한 초과
   * 먼저 실행한다.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update MailOutboxEntry e
         set e.status = com.itda.user.domain.MailDispatchStatus.FAILED,
             e.recipientEmail = null,
             e.nextAttemptAt = null,
             e.completedAt = :now,
             e.lastErrorKind = :lastErrorKind,
             e.updatedAt = :now
       where e.status = com.itda.user.domain.MailDispatchStatus.PENDING
         and e.nextAttemptAt <= :now
         and e.deliveryDeadlineAt <= :now
      """)
  int failDeadlineExceeded(
      @Param("now") LocalDateTime now, @Param("lastErrorKind") String lastErrorKind);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update MailOutboxEntry e
         set e.status = com.itda.user.domain.MailDispatchStatus.FAILED,
             e.recipientEmail = null,
             e.nextAttemptAt = null,
             e.completedAt = :now,
             e.lastErrorKind = :lastErrorKind,
             e.updatedAt = :now
       where e.status = com.itda.user.domain.MailDispatchStatus.PENDING
         and e.nextAttemptAt <= :now
         and e.attemptCount >= :maxAttempts
      """)
  int failMaxAttemptsExceeded(
      @Param("now") LocalDateTime now,
      @Param("maxAttempts") int maxAttempts,
      @Param("lastErrorKind") String lastErrorKind);

  /** 보존 기간이 지난 종결 행을 지운다. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from MailOutboxEntry e
       where e.status <> com.itda.user.domain.MailDispatchStatus.PENDING
         and e.completedAt < :threshold
      """)
  int deleteTerminalCompletedBefore(@Param("threshold") LocalDateTime threshold);
}
