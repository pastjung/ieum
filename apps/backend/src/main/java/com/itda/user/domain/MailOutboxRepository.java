package com.itda.user.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 메일 발송 작업 저장소의 도메인 port. JPA 등 기술 세부사항은 infrastructure가 구현한다.
 *
 * <p>다중 worker가 같은 테이블을 polling하는 전제에서 설계했다(ERD 3.5절). 배포 스크립트가 전환 후에도 이전 슬롯 컨테이너를 롤백용으로 유지하므로 구버전과
 * 신버전 backend가 동시에 실행될 수 있다. 그래서 조건부 선점과 가시성 만료가 선택이 아니라 필수다.
 */
public interface MailOutboxRepository {

  /**
   * 작업을 접수한다.
   *
   * <p>호출자가 이미 트랜잭션 안에 있으면 그 트랜잭션에 참여한다. 회원가입은 {@code users} INSERT와 같은 트랜잭션으로 커밋해야 하므로 새 트랜잭션을
   * 강제하지 않는다.
   */
  MailOutboxEntry save(MailOutboxEntry entry);

  /** 선점 후보를 읽는다. 조회 자체는 상태를 바꾸지 않는다. */
  List<MailOutboxEntry> findClaimCandidates(LocalDateTime now, int limit);

  /**
   * 조건부 UPDATE 한 번으로 선점한다. 조건에는 조회 당시의 {@code attemptCount}·{@code nextAttemptAt}과 기한·시도 상한을 함께
   * 넣는다. 갱신된 행이 없으면 다른 worker가 이미 가져간 것이다.
   *
   * @return 선점에 성공했으면 {@code true}
   */
  boolean claim(MailOutboxClaim claim, LocalDateTime now, int maxAttempts);

  /**
   * 재시도로 되돌린다. {@code status}를 {@code PENDING}으로 유지한 채 {@code nextAttemptAt}만 옮기고 수신 주소와 NULL인
   * {@code completedAt}을 유지한다.
   *
   * @param lastErrorKind 이번 실패의 내부 분류값 ({@link MailDispatchFailureKinds}). 이전 값을 남겨 두지 않고 항상 최신
   *     원인으로 덮어쓴다 — 오래된 분류가 남아 있으면 운영자가 현재 원인을 잘못 읽는다
   * @return 갱신된 행이 있으면 {@code true}. 가시성이 지나 다른 worker가 다시 선점했으면 {@code false}
   */
  boolean markRetry(
      MailOutboxClaim claim, LocalDateTime nextAttemptAt, String lastErrorKind, LocalDateTime now);

  /**
   * 종결한다. 수신 주소와 {@code nextAttemptAt}을 지우고 {@code completedAt}을 기록한다.
   *
   * @return 갱신된 행이 있으면 {@code true}
   */
  boolean markTerminal(
      MailOutboxClaim claim, MailDispatchStatus status, String lastErrorKind, LocalDateTime now);

  /**
   * 마지막 시도 도중 중단돼 스스로 종결하지 못한 작업을 정리한다. 기한이 지났거나 시도 횟수가 상한 이상인 {@code PENDING} 행을 {@code FAILED}로
   * 종결하며, 두 조건이 동시에 해당하면 기한 초과를 우선 기록한다.
   *
   * @return 종결한 행 수
   */
  int failStale(LocalDateTime now, int maxAttempts);

  /**
   * 보존 기간이 지난 종결 행을 지운다.
   *
   * @return 지운 행 수
   */
  int deleteTerminalCompletedBefore(LocalDateTime threshold);
}
