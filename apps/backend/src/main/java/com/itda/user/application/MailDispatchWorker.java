package com.itda.user.application;

import com.itda.user.application.port.out.ActionTokenClaims;
import com.itda.user.application.port.out.ActionTokenIssueResult;
import com.itda.user.application.port.out.ActionTokenRecord;
import com.itda.user.application.port.out.ActionTokenStore;
import com.itda.user.application.port.out.EmailVerificationTokenIssuer;
import com.itda.user.application.port.out.IssuedActionToken;
import com.itda.user.application.port.out.MailMessage;
import com.itda.user.application.port.out.MailProvider;
import com.itda.user.application.port.out.MailSendOutcome;
import com.itda.user.application.port.out.PasswordResetTokenIssuer;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.Email;
import com.itda.user.domain.MailDispatchFailureKinds;
import com.itda.user.domain.MailDispatchStatus;
import com.itda.user.domain.MailOutboxClaim;
import com.itda.user.domain.MailOutboxEntry;
import com.itda.user.domain.MailOutboxRepository;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRepository;
import com.itda.user.domain.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 메일 발송 작업을 한 번 처리하는 worker (ERD 3.5절).
 *
 * <p><b>스케줄러를 스스로 켜지 않는다.</b> {@code @Scheduled}도 {@code @EnableScheduling}도 없다. 실제 제공자 adapter가 없는
 * 상태에서 worker가 자동으로 돌면 발송할 수 없는 작업을 계속 시도하며 시도 횟수만 태운다. poll 주기·batch 크기·활성화 조건은 제공자 연동 이슈에서 정하고,
 * 지금은 호출자가 {@link #processOnce(int)}를 부른다.
 *
 * <p>다중 인스턴스를 전제로 한다. 배포 스크립트가 이전 슬롯 컨테이너를 롤백용으로 유지하므로 구버전과 신버전이 같은 테이블을 동시에 polling할 수 있다. 그래서 조건부
 * 선점과 가시성 만료가 필수다.
 *
 * <p><b>exactly-once를 주장하지 않는다.</b> 제공자 호출 뒤 DB 갱신이 실패하면 가시성 만료 후 다른 worker가 같은 작업을 다시 가져갈 수 있다.
 * 조건부 갱신이 보장하는 것은 DB 상태의 일관성이다.
 *
 * <p>로그에는 작업 id·목적·상태·시도 횟수·오류 분류값·요청 식별자만 남긴다. 수신 주소·사용자 식별자·토큰·제공자 원문 오류·예외 message는 남기지 않는다.
 */
public class MailDispatchWorker {

  /** 처리 시도마다 새로 만드는 요청 식별자의 MDC 키. 요청 경로의 {@code requestId}와 같은 자리를 쓴다. */
  private static final String REQUEST_ID_KEY = "requestId";

  /**
   * 한 처리 시도 안에서 action token 발급을 다시 해 보는 횟수.
   *
   * <p>API 명세 3.6.1절은 {@code ALREADY_EXISTS}에 대해 "application은 새 UUID를 만들어 유한 횟수로 다시 시도한다"고 정한다.
   * UUID v4 충돌은 사실상 일어나지 않으므로 상한은 작아도 되고, 무한 재시도는 저장소 이상 상황에서 worker를 묶어 둔다.
   */
  private static final int MAX_TOKEN_ISSUE_ATTEMPTS = 3;

  /**
   * 예상하지 못한 예외의 로그 분류값.
   *
   * <p>{@code mail_outbox.last_error_kind}에는 쓰지 않는다. 예외가 난 작업은 상태를 바꾸지 못한 채 가시성 만료를 기다리므로 기록할 행 갱신
   * 자체가 없다.
   */
  private static final String UNEXPECTED_FAILURE_KIND = "UNEXPECTED";

  private static final Logger log = LoggerFactory.getLogger(MailDispatchWorker.class);

  private final MailOutboxRepository mailOutboxRepository;
  private final UserRepository userRepository;
  private final ActionTokenStore actionTokenStore;
  private final EmailVerificationTokenIssuer emailVerificationTokenIssuer;
  private final PasswordResetTokenIssuer passwordResetTokenIssuer;
  private final MailProvider mailProvider;
  private final MailDispatchPolicy policy;
  private final Clock clock;

  public MailDispatchWorker(
      MailOutboxRepository mailOutboxRepository,
      UserRepository userRepository,
      ActionTokenStore actionTokenStore,
      EmailVerificationTokenIssuer emailVerificationTokenIssuer,
      PasswordResetTokenIssuer passwordResetTokenIssuer,
      MailProvider mailProvider,
      MailDispatchPolicy policy,
      Clock clock) {
    this.mailOutboxRepository = mailOutboxRepository;
    this.userRepository = userRepository;
    this.actionTokenStore = actionTokenStore;
    this.emailVerificationTokenIssuer = emailVerificationTokenIssuer;
    this.passwordResetTokenIssuer = passwordResetTokenIssuer;
    this.mailProvider = mailProvider;
    this.policy = policy;
    this.clock = clock;
  }

  /**
   * 처리 가능한 작업을 최대 {@code batchSize}건까지 한 번 처리한다.
   *
   * <p>요청 식별자는 batch 단위가 아니라 <b>처리 시도 단위</b>로 만든다 (API 명세 3.8절 「로그의 요청 식별자」: "worker는 각 처리 시도를 시작할
   * 때 새 UUID v4 요청 식별자를 만들어 그 시도의 구조화 로그에 쓴다"). 후보 조회와 선점 경쟁은 아직 어떤 작업의 처리 시도도 아니므로 식별자를 붙이지 않고,
   * 선점에 성공한 순간부터 그 작업만의 식별자를 연다.
   *
   * @return 선점에 성공한 건수. 처리 도중 예외가 난 작업도 이미 시도 횟수를 소비했으므로 포함한다
   */
  public int processOnce(int batchSize) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batch 크기는 1 이상이어야 합니다.");
    }
    List<MailOutboxEntry> candidates =
        this.mailOutboxRepository.findClaimCandidates(now(), batchSize);
    int claimed = 0;
    for (MailOutboxEntry candidate : candidates) {
      MailOutboxClaim claim = toClaim(candidate, now());
      if (!this.mailOutboxRepository.claim(claim, now(), this.policy.maxAttempts())) {
        // 다른 worker가 이미 가져갔다. 업무 경쟁의 정상 결과이지 장애가 아니며, 처리 시도가 아니므로 식별자도 만들지 않는다.
        log.debug("메일 작업을 선점하지 못했습니다. outboxId={} purpose={}", claim.id(), claim.purpose());
        continue;
      }
      claimed++;
      withRequestId(() -> dispatchSafely(claim));
    }
    return claimed;
  }

  /** 기한·시도 초과로 남겨진 작업을 종결하고 보존 기간이 지난 종결 행을 지운다. */
  public int cleanUp() {
    return withRequestId(
        () -> {
          LocalDateTime now = now();
          int failed = this.mailOutboxRepository.failStale(now, this.policy.maxAttempts());
          int deleted =
              this.mailOutboxRepository.deleteTerminalCompletedBefore(
                  now.minus(this.policy.terminalRetention()));
          if (failed > 0 || deleted > 0) {
            log.info("메일 작업을 정리했습니다. failed={} deleted={}", failed, deleted);
          }
          return failed + deleted;
        });
  }

  /**
   * 새 요청 식별자를 열고 실행한 뒤 이전 값을 되돌린다.
   *
   * <p>{@code remove}가 아니라 <b>복원</b>이다. worker가 이미 MDC를 쓰는 실행 문맥에서 불릴 수 있는데 무조건 지우면 그 문맥의 식별자까지
   * 없앤다. 반대로 되돌리지 않으면, 스레드 풀이 스레드를 재사용하므로 다음 작업의 로그에 남의 식별자가 붙는다.
   */
  private <T> T withRequestId(Supplier<T> body) {
    String previous = MDC.get(REQUEST_ID_KEY);
    MDC.put(REQUEST_ID_KEY, UUID.randomUUID().toString());
    try {
      return body.get();
    } finally {
      if (previous == null) {
        MDC.remove(REQUEST_ID_KEY);
      } else {
        MDC.put(REQUEST_ID_KEY, previous);
      }
    }
  }

  /**
   * 한 작업의 예외가 batch 전체를 멈추지 않게 막는다.
   *
   * <p>계약에 명시되지 않은 판단이라 근거를 남긴다. 뒤 작업까지 함께 버리면 아직 선점되지도 않은 무관한 작업들이 기한까지 밀린다. 반면 예외가 난 작업은 이미 선점돼
   * {@code next_attempt_at}이 가시성 만료로 밀려 있으므로, 만료 뒤 정상적으로 다시 선점된다 — 잃는 것은 그 작업의 시도 한 번뿐이다.
   *
   * <p><b>예외에서 아무것도 꺼내 쓰지 않는다.</b> message·stack trace·예외 객체는 물론 클래스 이름도 남기지 않는다. 제공자 client와 JDBC
   * 예외에는 수신 주소·토큰·SQL이 들어갈 수 있고, 예외 클래스 이름은 그 자체로 내부 구현을 드러내면서 값이 열려 있어 안정된 진단 필드가 되지 못한다 (API 명세
   * 3.8절 금지 항목). 대신 안정된 필드 넷만 남긴다 — {@code requestId}(MDC)·{@code outboxId}·{@code stage}·{@code
   * failureKind}.
   *
   * <p>그래서 이 로그만으로는 원인을 특정할 수 없다. 어느 단계에서 끊겼는지({@code stage})와 그 작업이 다시 선점된다는 사실까지가 이 로그의 역할이다.
   */
  private boolean dispatchSafely(MailOutboxClaim claim) {
    Stage stage = new Stage();
    try {
      dispatch(claim, stage);
      return true;
    } catch (RuntimeException e) {
      log.error(
          "메일 작업 처리에 실패했습니다. 가시성 만료 뒤 다시 선점됩니다."
              + " outboxId={} purpose={} attempt={} stage={} failureKind={}",
          claim.id(),
          claim.purpose(),
          claim.attemptCount(),
          stage.current,
          UNEXPECTED_FAILURE_KIND);
      return false;
    }
  }

  /** 예외가 났을 때 어느 단계였는지만 알려 주는 표시. 값은 코드가 정한 닫힌 목록이라 로그 필드로 안정적이다. */
  private static final class Stage {
    private static final String ELIGIBILITY = "ELIGIBILITY";
    private static final String TOKEN_ISSUE = "TOKEN_ISSUE";
    private static final String PROVIDER_CALL = "PROVIDER_CALL";
    private static final String STATE_UPDATE = "STATE_UPDATE";

    private String current = ELIGIBILITY;
  }

  private MailOutboxClaim toClaim(MailOutboxEntry candidate, LocalDateTime now) {
    return new MailOutboxClaim(
        candidate.getId(),
        candidate.getPurpose(),
        candidate.getRecipientEmail(),
        candidate.getAttemptCount() + 1,
        candidate.getNextAttemptAt(),
        MailOutboxEntry.truncate(now.plus(this.policy.visibilityTimeout())),
        candidate.getDeliveryDeadlineAt());
  }

  private void dispatch(MailOutboxClaim claim, Stage stage) {
    stage.current = Stage.ELIGIBILITY;
    Optional<User> user = findEligibleUser(claim);
    if (user.isEmpty()) {
      // 계정 부재·비활성·이미 인증됨은 발송 대상이 아니어서 끝난 정상 결과다.
      stage.current = Stage.STATE_UPDATE;
      terminate(claim, MailDispatchStatus.SKIPPED, null);
      return;
    }
    stage.current = Stage.TOKEN_ISSUE;
    TokenIssue issue = issueActionToken(claim, user.get());
    if (issue.token() == null) {
      // 토큰을 등록하지 못했다. 상태를 바꾸지 못했을 뿐이므로 그 원인을 남기고 재시도한다.
      stage.current = Stage.STATE_UPDATE;
      retry(claim, null, issue.failureKind());
      return;
    }
    stage.current = Stage.PROVIDER_CALL;
    MailSendOutcome outcome =
        this.mailProvider.send(
            new MailMessage(claim.purpose(), claim.recipientEmail(), issue.token().compactToken()));
    stage.current = Stage.STATE_UPDATE;
    applyProviderOutcome(claim, outcome);
  }

  /**
   * 발송 적격성을 판정한다 (API 명세 3.4.1·3.4.2절).
   *
   * <p>EMAIL_VERIFICATION은 {@code ACTIVE}이면서 미인증인 계정에만, PASSWORD_RESET은 {@code ACTIVE}이면 보내며 이메일 인증
   * 여부를 조건에 넣지 않는다. 미인증 계정도 재설정 대상이기 때문이며 의도적으로 다르다.
   */
  private Optional<User> findEligibleUser(MailOutboxClaim claim) {
    return this.userRepository
        .findByEmail(Email.of(claim.recipientEmail()))
        .filter(user -> user.getStatus() == UserStatus.ACTIVE)
        .filter(
            user ->
                claim.purpose() != AccountActionPurpose.EMAIL_VERIFICATION
                    || user.getEmailVerifiedAt() == null);
  }

  /**
   * 각 시도 직전에 새 토큰을 발급한다. 대기 시간이 토큰 유효기간을 깎지 않게 하는 계약이다 (ERD 3.5절).
   *
   * <p>{@code ALREADY_EXISTS}는 새 {@code jti}가 이미 있다는 뜻이므로 <b>새 토큰을 다시 발급해</b> 유한 횟수만큼 시도한다 (API 명세
   * 3.6.1절). {@code UNAVAILABLE}은 저장소가 응답하지 못한 것이라 같은 발급을 반복해도 달라지지 않으므로 즉시 outbox 재시도로 넘긴다.
   *
   * <p>실패한 발급 시도의 토큰 원문·해시·{@code jti}는 어디에도 남기지 않는다.
   *
   * @return 성공하면 토큰을, 실패하면 {@code null} 토큰과 재시도 행에 기록할 실패 분류값
   */
  private TokenIssue issueActionToken(MailOutboxClaim claim, User user) {
    for (int attempt = 1; attempt <= MAX_TOKEN_ISSUE_ATTEMPTS; attempt++) {
      IssuedActionToken issued =
          switch (claim.purpose()) {
            case EMAIL_VERIFICATION ->
                this.emailVerificationTokenIssuer.issue(new ActionTokenClaims(user.getId()));
            case PASSWORD_RESET ->
                this.passwordResetTokenIssuer.issue(new ActionTokenClaims(user.getId()));
          };
      ActionTokenIssueResult result =
          this.actionTokenStore.issueActionToken(
              new ActionTokenRecord(
                  user.getId(),
                  claim.purpose(),
                  issued.jti(),
                  issued.tokenHash(),
                  issued.expiresAtEpochSecond()));
      if (result == ActionTokenIssueResult.ISSUED) {
        return new TokenIssue(issued, null);
      }
      if (result != ActionTokenIssueResult.ALREADY_EXISTS) {
        log.warn("계정 작업 토큰 상태를 저장하지 못했습니다. outboxId={} purpose={}", claim.id(), claim.purpose());
        return new TokenIssue(null, MailDispatchFailureKinds.ACTION_TOKEN_STORE_UNAVAILABLE);
      }
      log.warn(
          "계정 작업 토큰 식별자가 이미 있어 다시 발급합니다. outboxId={} purpose={} attempt={}",
          claim.id(),
          claim.purpose(),
          attempt);
    }
    log.warn(
        "계정 작업 토큰 식별자 충돌이 이어져 발급을 포기했습니다. outboxId={} purpose={}", claim.id(), claim.purpose());
    return new TokenIssue(null, MailDispatchFailureKinds.ACTION_TOKEN_ID_COLLISION);
  }

  /** 토큰 발급 시도의 결과. 실패면 {@code token}이 {@code null}이고 {@code failureKind}가 채워진다. */
  private record TokenIssue(IssuedActionToken token, String failureKind) {}

  private void applyProviderOutcome(MailOutboxClaim claim, MailSendOutcome outcome) {
    switch (outcome.result()) {
      case ACCEPTED -> terminate(claim, MailDispatchStatus.PROVIDER_ACCEPTED, null);
      case REJECTED ->
          terminate(claim, MailDispatchStatus.FAILED, MailDispatchFailureKinds.PROVIDER_REJECTED);
      // 인증·설정 오류도 기한과 최대 시도 범위 안에서 재시도한다 (ERD 3.5절).
      // UNCERTAIN은 접수 여부를 확정할 수 없으므로 성공으로도 영구 실패로도 단정하지 않는다.
      case AUTH_CONFIG_ERROR ->
          retry(
              claim,
              outcome.providerRetryAt(),
              MailDispatchFailureKinds.PROVIDER_AUTH_CONFIG_ERROR);
      case RETRYABLE ->
          retry(claim, outcome.providerRetryAt(), MailDispatchFailureKinds.PROVIDER_RETRYABLE);
      case UNCERTAIN ->
          retry(claim, outcome.providerRetryAt(), MailDispatchFailureKinds.PROVIDER_UNCERTAIN);
    }
  }

  /**
   * 재시도 시각을 잡는다. 계산한 시각이 기한 이상이면 기한 초과로, 시도 횟수가 상한에 도달했으면 시도 초과로 종결한다 (ERD 3.5절 판정 순서).
   *
   * @param providerRetryAt 제공자가 지시한 절대 재시도 시각. 없으면 {@code null}
   * @param failureKind 이번 실패의 내부 분류값. 재시도 행에 기록해 다음 실패가 덮어쓴다
   */
  private void retry(MailOutboxClaim claim, Instant providerRetryAt, String failureKind) {
    LocalDateTime now = now();
    LocalDateTime nextAttemptAt = nextAttemptAt(claim, now, providerRetryAt);
    if (!now.isBefore(claim.deliveryDeadlineAt())
        || !nextAttemptAt.isBefore(claim.deliveryDeadlineAt())) {
      terminate(
          claim, MailDispatchStatus.FAILED, MailDispatchFailureKinds.DELIVERY_DEADLINE_EXCEEDED);
      return;
    }
    if (claim.attemptCount() >= this.policy.maxAttempts()) {
      terminate(claim, MailDispatchStatus.FAILED, MailDispatchFailureKinds.MAX_ATTEMPTS_EXCEEDED);
      return;
    }
    boolean updated = this.mailOutboxRepository.markRetry(claim, nextAttemptAt, failureKind, now);
    logUpdate(claim, "RETRY", updated, failureKind);
  }

  /**
   * 제공자가 지시한 시각이 있으면 그대로 쓴다.
   *
   * <p>ERD 3.5절: "내부 지수 backoff의 상한은 그 지시를 더 이른 시각으로 앞당기는 근거로 쓰지 않는다." 그래서 내부 상한으로 자르지 않고 지터도 섞지
   * 않는다 — 지터는 우리가 만든 시각을 분산시키는 장치지 제공자의 지시를 흔들라는 뜻이 아니다.
   *
   * <p><b>현재 시각 이하인 지시는 쓰지 않고 내부 backoff로 되돌린다.</b> 그대로 쓰면 {@code next_attempt_at}이 이미 지난 시각이 되어 다음
   * poll에서 곧바로 다시 후보가 되고, 제공자가 계속 같은 지시를 주면 시도 상한을 태울 때까지 즉시 재시도가 반복된다. 내부 backoff는 항상 현재보다 뒤이므로 이
   * 되돌림이 제공자가 지시한 시각을 <b>앞당기는</b> 일은 없다 — 계약이 금지한 것은 앞당기는 쪽이다.
   *
   * <p>경계: 지시가 과거이거나 현재와 같으면 내부 backoff, 미래면 그 값을 그대로 쓴다. 기한 판정은 호출자가 이 값으로 다시 하므로 기한 이후 지시는 여기서
   * 걸러내지 않고 {@code DELIVERY_DEADLINE_EXCEEDED} 종결로 간다.
   */
  private LocalDateTime nextAttemptAt(
      MailOutboxClaim claim, LocalDateTime now, Instant providerRetryAt) {
    if (providerRetryAt != null) {
      LocalDateTime instructed =
          MailOutboxEntry.truncate(LocalDateTime.ofInstant(providerRetryAt, this.clock.getZone()));
      if (instructed.isAfter(now)) {
        return instructed;
      }
    }
    return MailOutboxEntry.truncate(now.plus(nextDelay(claim)));
  }

  private void terminate(MailOutboxClaim claim, MailDispatchStatus status, String failureKind) {
    boolean updated = this.mailOutboxRepository.markTerminal(claim, status, failureKind, now());
    logUpdate(claim, status.name(), updated, failureKind);
  }

  /** 지터는 여기서만 적용한다. 난수를 섞는 지점을 한 곳으로 모아 나머지 계산을 결정적으로 남긴다. */
  private Duration nextDelay(MailOutboxClaim claim) {
    Duration base = this.policy.backoffFor(claim.attemptCount());
    double jitter = this.policy.jitterRatio();
    if (jitter <= 0) {
      return base;
    }
    double factor = 1 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
    return Duration.ofMillis(Math.max(1, Math.round(base.toMillis() * factor)));
  }

  private void logUpdate(
      MailOutboxClaim claim, String transition, boolean updated, String failureKind) {
    if (updated) {
      log.info(
          "메일 작업 상태를 바꿨습니다. outboxId={} purpose={} transition={} attempt={} errorKind={}",
          claim.id(),
          claim.purpose(),
          transition,
          claim.attemptCount(),
          failureKind);
      return;
    }
    // 가시성이 지나 다른 worker가 다시 선점했다. 늦은 갱신이 최신 상태를 덮어쓰지 못한 정상 결과다.
    log.info(
        "메일 작업 상태를 바꾸지 못했습니다. 다른 worker가 다시 선점했습니다. outboxId={} purpose={} transition={} attempt={}",
        claim.id(),
        claim.purpose(),
        transition,
        claim.attemptCount());
  }

  private LocalDateTime now() {
    return MailOutboxEntry.truncate(LocalDateTime.now(this.clock));
  }
}
