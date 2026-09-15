package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.Email;
import com.itda.user.domain.MailDispatchFailureKinds;
import com.itda.user.domain.MailDispatchStatus;
import com.itda.user.domain.MailOutboxClaim;
import com.itda.user.domain.MailOutboxEntry;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.ActionTokenStore;
import com.itda.user.repository.MailOutboxRepository;
import com.itda.user.repository.UserRepository;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * worker의 판정과 상태 전이를 저장소 없이 고정한다.
 *
 * <p>조건부 선점이 실제로 경쟁을 막는지는 DB가 있어야 증명되므로 여기서 다루지 않는다. 이 테스트가 고정하는 것은 "무엇을 건너뛰고, 언제 재시도하고, 어떤 분류로
 * 종결하고, 어떤 로그 문맥에서 처리하는가"라는 판정 규칙이다.
 */
class MailDispatchWorkerTest {

  private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
  private static final String EMAIL = "learner@example.com";
  private static final String REQUEST_ID = "requestId";

  private static final AtomicLong ID_SEQUENCE = new AtomicLong();

  private RecordingMailOutboxRepository outbox;
  private StubUserRepository users;
  private RecordingActionTokenStore actionTokens;
  private RecordingMailProvider provider;
  private StubActionTokenIssuer emailIssuer;
  private MailDispatchWorker worker;

  private ch.qos.logback.classic.Logger workerLogger;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() {
    MDC.clear();
    newWorker();
    this.workerLogger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MailDispatchWorker.class);
    this.workerLogger.setLevel(Level.DEBUG);
    this.logs = new ListAppender<>();
    this.logs.start();
    this.workerLogger.addAppender(this.logs);
  }

  @AfterEach
  void tearDown() {
    this.workerLogger.detachAppender(this.logs);
    this.workerLogger.setLevel(null);
    MDC.clear();
  }

  /** stub을 전부 새로 만든다. 한 테스트가 여러 결과를 차례로 확인할 때도 쓴다. */
  private void newWorker() {
    this.outbox = new RecordingMailOutboxRepository();
    this.users = new StubUserRepository();
    this.actionTokens = new RecordingActionTokenStore();
    this.provider = new RecordingMailProvider(MailProvider.SendResult.ACCEPTED);
    this.emailIssuer = new StubActionTokenIssuer(AccountActionPurpose.EMAIL_VERIFICATION);
    this.worker =
        new MailDispatchWorker(
            this.outbox,
            this.users,
            this.actionTokens,
            this.emailIssuer,
            new StubActionTokenIssuer(AccountActionPurpose.PASSWORD_RESET),
            this.provider,
            new FixedPolicy(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private LocalDateTime now() {
    return MailOutboxEntry.truncate(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
  }

  private void pendingJob(AccountActionPurpose purpose) {
    this.outbox.candidates.add(
        withId(MailOutboxEntry.enqueue(purpose, EMAIL, now().plusHours(24), now())));
  }

  /** 로그 이벤트에 붙은 요청 식별자들. */
  private List<String> loggedRequestIds() {
    return this.logs.list.stream().map(event -> event.getMDCPropertyMap().get(REQUEST_ID)).toList();
  }

  private List<String> loggedMessages() {
    return this.logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /**
   * id는 DB가 발급한다. 저장소 없이 판정 규칙만 보는 테스트라 여기서 채워 준다.
   *
   * <p>엔티티에 setter를 만들지 않는다는 규칙을 테스트 편의로 깨지 않으려고 리플렉션을 쓴다.
   */
  private static MailOutboxEntry withId(MailOutboxEntry entry) {
    return withId(entry, "id", ID_SEQUENCE.incrementAndGet());
  }

  private static void withAttemptCount(MailOutboxEntry entry, int attemptCount) {
    try {
      java.lang.reflect.Field field = MailOutboxEntry.class.getDeclaredField("attemptCount");
      field.setAccessible(true);
      field.setInt(entry, attemptCount);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("테스트 fixture에 시도 횟수를 넣지 못했습니다.", e);
    }
  }

  private static <T> T withId(T target, String fieldName, Long value) {
    try {
      java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
      return target;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("테스트 fixture에 id를 넣지 못했습니다.", e);
    }
  }

  private void activeUser(LocalDateTime emailVerifiedAt) {
    User user = User.create("learner01", "argon2id$hash", UserRole.LEARNER, Email.of(EMAIL), now());
    if (emailVerifiedAt != null) {
      user.verifyEmail(emailVerifiedAt);
    }
    // User의 id도 DB가 발급한다. worker가 토큰 발급에 쓰므로 fixture에서 채운다.
    this.users.user = withId(user, "id", 7L);
  }

  // ---------- 적격성 ----------

  @Test
  void 계정이_없으면_SKIPPED로_종결한다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);

    assertThat(this.worker.processOnce(10)).isEqualTo(1);

    assertThat(this.outbox.terminalStatus).isEqualTo(MailDispatchStatus.SKIPPED);
    assertThat(this.outbox.terminalFailureKind).isNull();
    assertThat(this.provider.sendCount()).isZero();
    assertThat(this.actionTokens.issued).isZero();
  }

  @Test
  void 이미_인증된_계정에는_인증_메일을_보내지_않는다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(now().minusDays(1));

    this.worker.processOnce(10);

    assertThat(this.outbox.terminalStatus).isEqualTo(MailDispatchStatus.SKIPPED);
    assertThat(this.provider.sendCount()).isZero();
  }

  @Test
  void 미인증_계정에도_비밀번호_재설정_메일은_보낸다() {
    // 이메일 인증 요청과 의도적으로 다른 규칙이다 (API 명세 3.4.2절).
    pendingJob(AccountActionPurpose.PASSWORD_RESET);
    activeUser(null);

    this.worker.processOnce(10);

    assertThat(this.outbox.terminalStatus).isEqualTo(MailDispatchStatus.PROVIDER_ACCEPTED);
    assertThat(this.provider.sendCount()).isEqualTo(1);
  }

  @Test
  void 비활성_계정은_SKIPPED로_종결한다() {
    pendingJob(AccountActionPurpose.PASSWORD_RESET);
    activeUser(null);
    this.users.user.withdraw(
        "deleted-user", Email.of("deleted@example.invalid"), "argon2id$x", now());

    this.worker.processOnce(10);

    assertThat(this.outbox.terminalStatus).isEqualTo(MailDispatchStatus.SKIPPED);
    assertThat(this.provider.sendCount()).isZero();
  }

  // ---------- 제공자 결과 ----------

  @Test
  void 접수되면_PROVIDER_ACCEPTED로_종결하고_토큰을_먼저_등록한다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);

    this.worker.processOnce(10);

    assertThat(this.actionTokens.issued).isEqualTo(1);
    assertThat(this.outbox.terminalStatus).isEqualTo(MailDispatchStatus.PROVIDER_ACCEPTED);
    assertThat(this.outbox.terminalFailureKind).isNull();
    assertThat(this.provider.sent())
        .singleElement()
        .satisfies(
            sent -> {
              assertThat(sent.purpose()).isEqualTo("EMAIL_VERIFICATION");
              assertThat(sent.hadToken()).isTrue();
            });
  }

  @Test
  void 결정적_거부는_FAILED로_종결한다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    this.provider.willReturn(MailProvider.SendResult.REJECTED);

    this.worker.processOnce(10);

    assertThat(this.outbox.terminalStatus).isEqualTo(MailDispatchStatus.FAILED);
    assertThat(this.outbox.terminalFailureKind)
        .isEqualTo(MailDispatchFailureKinds.PROVIDER_REJECTED);
  }

  @Test
  void 재시도_가능한_결과와_불확실한_결과는_PENDING으로_남기고_원인을_기록한다() {
    Map<MailProvider.SendResult, String> expected =
        Map.of(
            MailProvider.SendResult.RETRYABLE, MailDispatchFailureKinds.PROVIDER_RETRYABLE,
            MailProvider.SendResult.UNCERTAIN, MailDispatchFailureKinds.PROVIDER_UNCERTAIN,
            MailProvider.SendResult.AUTH_CONFIG_ERROR,
                MailDispatchFailureKinds.PROVIDER_AUTH_CONFIG_ERROR);

    expected.forEach(
        (result, failureKind) -> {
          newWorker();
          pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
          activeUser(null);
          this.provider.willReturn(result);

          this.worker.processOnce(10);

          assertThat(this.outbox.terminalStatus).as("%s", result).isNull();
          assertThat(this.outbox.retriedAt).as("%s", result).isNotNull();
          assertThat(this.outbox.retryFailureKind).as("%s", result).isEqualTo(failureKind);
        });
  }

  @Test
  void 토큰_저장소_장애로_재시도할_때_원인을_기록한다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    this.actionTokens.result = ActionTokenStore.IssueResult.UNAVAILABLE;

    this.worker.processOnce(10);

    assertThat(this.provider.sendCount()).isZero();
    assertThat(this.outbox.terminalStatus).isNull();
    assertThat(this.outbox.retriedAt).isNotNull();
    assertThat(this.outbox.retryFailureKind)
        .isEqualTo(MailDispatchFailureKinds.ACTION_TOKEN_STORE_UNAVAILABLE);
  }

  @Test
  void 식별자_충돌_소진으로_재시도할_때_원인을_기록한다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    this.actionTokens.result = ActionTokenStore.IssueResult.ALREADY_EXISTS;

    this.worker.processOnce(10);

    assertThat(this.outbox.retryFailureKind)
        .isEqualTo(MailDispatchFailureKinds.ACTION_TOKEN_ID_COLLISION);
  }

  @Test
  void 접수와_정상_건너뜀_종결에는_실패_분류를_남기지_않는다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);

    this.worker.processOnce(10);

    assertThat(this.outbox.terminalStatus).isEqualTo(MailDispatchStatus.PROVIDER_ACCEPTED);
    assertThat(this.outbox.terminalFailureKind).isNull();

    newWorker();
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);

    this.worker.processOnce(10);

    assertThat(this.outbox.terminalStatus).isEqualTo(MailDispatchStatus.SKIPPED);
    assertThat(this.outbox.terminalFailureKind).isNull();
  }

  // ---------- 제공자가 지정한 재시도 시각 (ERD 3.5절) ----------

  @Test
  void 제공자가_지정한_재시도_시각을_그대로_존중한다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    Instant instructed = NOW.plus(Duration.ofMinutes(2));
    this.provider.willReturn(MailProvider.SendResult.RETRYABLE, instructed);

    this.worker.processOnce(10);

    // 내부 backoff는 30초지만 제공자가 2분을 지시했다. 더 이른 시각으로 앞당기지 않는다.
    assertThat(this.outbox.retriedAt)
        .isEqualTo(MailOutboxEntry.truncate(LocalDateTime.ofInstant(instructed, ZoneOffset.UTC)));
  }

  @Test
  void 제공자_지정_시각은_내부_backoff_상한으로_앞당기지_않는다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    // 내부 상한(10분)보다 훨씬 뒤를 지시했다.
    this.provider.willReturn(
        MailProvider.SendResult.AUTH_CONFIG_ERROR, NOW.plus(Duration.ofMinutes(45)));

    this.worker.processOnce(10);

    assertThat(this.outbox.retriedAt).isEqualTo(now().plusMinutes(45));
  }

  @Test
  void 제공자_지정_시각이_기한_이상이면_기한_초과로_종결한다() {
    this.outbox.candidates.add(
        withId(
            MailOutboxEntry.enqueue(
                AccountActionPurpose.PASSWORD_RESET, EMAIL, now().plusMinutes(30), now())));
    activeUser(null);
    this.provider.willReturn(MailProvider.SendResult.UNCERTAIN, NOW.plus(Duration.ofMinutes(45)));

    this.worker.processOnce(10);

    assertThat(this.outbox.terminalStatus).isEqualTo(MailDispatchStatus.FAILED);
    assertThat(this.outbox.terminalFailureKind)
        .isEqualTo(MailDispatchFailureKinds.DELIVERY_DEADLINE_EXCEEDED);
    assertThat(this.outbox.retriedAt).isNull();
  }

  @Test
  void 지정_시각이_없으면_내부_backoff를_쓴다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    this.provider.willReturn(MailProvider.SendResult.RETRYABLE);

    this.worker.processOnce(10);

    assertThat(this.outbox.retriedAt).isEqualTo(now().plusSeconds(30));
  }

  @Test
  void 이미_지난_지정_시각은_쓰지_않고_내부_backoff로_되돌린다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    // 그대로 쓰면 next_attempt_at이 과거가 되어 다음 poll에서 곧바로 다시 후보가 된다.
    this.provider.willReturn(MailProvider.SendResult.RETRYABLE, NOW.minus(Duration.ofMinutes(5)));

    this.worker.processOnce(10);

    assertThat(this.outbox.retriedAt).isEqualTo(now().plusSeconds(30)).isAfter(now());
  }

  @Test
  void 현재와_같은_지정_시각도_내부_backoff로_되돌린다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    this.provider.willReturn(MailProvider.SendResult.RETRYABLE, NOW);

    this.worker.processOnce(10);

    assertThat(this.outbox.retriedAt).isEqualTo(now().plusSeconds(30)).isAfter(now());
  }

  @Test
  void 미래이며_기한_이내인_지정_시각은_그대로_존중한다() {
    // 기한은 24시간 뒤다. 그 안의 미래 시각이면 내부 계산으로 바꾸지 않는다.
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    this.provider.willReturn(MailProvider.SendResult.RETRYABLE, NOW.plus(Duration.ofHours(23)));

    this.worker.processOnce(10);

    assertThat(this.outbox.retriedAt).isEqualTo(now().plusHours(23));
    assertThat(this.outbox.terminalStatus).isNull();
  }

  @Test
  void 재시도하지_않는_결과에는_재시도_시각을_넣을_수_없다() {
    Instant anyTime = NOW.plus(Duration.ofMinutes(1));

    assertThatThrownBy(
            () -> MailProvider.SendOutcome.retryAt(MailProvider.SendResult.ACCEPTED, anyTime))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> MailProvider.SendOutcome.retryAt(MailProvider.SendResult.REJECTED, anyTime))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new MailProvider.SendOutcome(MailProvider.SendResult.ACCEPTED, anyTime))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 제공자_결과에는_원문_오류를_담을_자리가_없다() {
    // 담을 자리를 만들지 않는 것이 "제공자 원문 오류를 남기지 않는다"를 지키는 방법이다 (API 명세 3.8절).
    List<Class<?>> components =
        Arrays.stream(MailProvider.SendOutcome.class.getRecordComponents())
            .map(RecordComponent::getType)
            .toList();

    assertThat(components).containsExactly(MailProvider.SendResult.class, Instant.class);
    assertThat(MailProvider.SendResult.values()).hasSize(5);
  }

  // ---------- 토큰 식별자 충돌의 유한 재시도 (API 명세 3.6.1절) ----------

  @Test
  void 식별자가_이미_있으면_새_토큰으로_다시_발급한다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    this.actionTokens.queue(
        ActionTokenStore.IssueResult.ALREADY_EXISTS, ActionTokenStore.IssueResult.ISSUED);

    this.worker.processOnce(10);

    assertThat(this.emailIssuer.issuedCount()).isEqualTo(2);
    assertThat(this.actionTokens.issued).isEqualTo(2);
    assertThat(this.provider.sendCount()).isEqualTo(1);
    assertThat(this.outbox.terminalStatus).isEqualTo(MailDispatchStatus.PROVIDER_ACCEPTED);
  }

  @Test
  void 제공자에는_최종_저장에_성공한_토큰만_전달한다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    this.actionTokens.queue(
        ActionTokenStore.IssueResult.ALREADY_EXISTS, ActionTokenStore.IssueResult.ISSUED);

    this.worker.processOnce(10);

    // 첫 발급은 버려진다. 두 번째 토큰만 나가야 한다 — 원문 대신 계약과 같은 규칙의 해시로 비교한다.
    String discarded = CompactTokenHasher.hash(this.emailIssuer.issuedAt(0).compactToken());
    String delivered = CompactTokenHasher.hash(this.emailIssuer.issuedAt(1).compactToken());
    assertThat(delivered).isNotEqualTo(discarded);
    assertThat(this.provider.sent())
        .singleElement()
        .satisfies(sent -> assertThat(sent.tokenHash()).isEqualTo(delivered));
  }

  @Test
  void 세_번_연속_충돌하면_보내지_않고_재시도한다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    this.actionTokens.result = ActionTokenStore.IssueResult.ALREADY_EXISTS;

    this.worker.processOnce(10);

    assertThat(this.emailIssuer.issuedCount()).isEqualTo(3);
    assertThat(this.provider.sendCount()).isZero();
    assertThat(this.outbox.terminalStatus).isNull();
    assertThat(this.outbox.retriedAt).isNotNull();
  }

  @Test
  void 저장소_장애에서는_발급을_반복하지_않는다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    this.actionTokens.result = ActionTokenStore.IssueResult.UNAVAILABLE;

    this.worker.processOnce(10);

    // UNAVAILABLE은 jti 충돌이 아니다. 같은 발급을 반복해도 달라지지 않는다.
    assertThat(this.emailIssuer.issuedCount()).isEqualTo(1);
    assertThat(this.actionTokens.issued).isEqualTo(1);
    assertThat(this.provider.sendCount()).isZero();
  }

  @Test
  void 발급_실패_로그에_토큰_원문이나_식별자를_남기지_않는다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    this.actionTokens.result = ActionTokenStore.IssueResult.ALREADY_EXISTS;

    this.worker.processOnce(10);

    List<String> messages = loggedMessages();
    assertThat(messages).isNotEmpty();
    for (IssuedActionToken issued : this.emailIssuer.issued()) {
      assertThat(messages)
          .noneMatch(message -> message.contains(issued.compactToken()))
          .noneMatch(message -> message.contains(issued.jti()))
          .noneMatch(message -> message.contains(issued.tokenHash()));
    }
    assertThat(messages).noneMatch(message -> message.contains(EMAIL));
  }

  // ---------- 재시도 종결 판정 ----------

  @Test
  void 다음_시도가_기한을_넘으면_기한_초과로_종결한다() {
    this.outbox.candidates.add(
        withId(
            MailOutboxEntry.enqueue(
                AccountActionPurpose.PASSWORD_RESET, EMAIL, now().plusSeconds(10), now())));
    activeUser(null);
    this.provider.willReturn(MailProvider.SendResult.RETRYABLE);

    this.worker.processOnce(10);

    assertThat(this.outbox.terminalStatus).isEqualTo(MailDispatchStatus.FAILED);
    assertThat(this.outbox.terminalFailureKind)
        .isEqualTo(MailDispatchFailureKinds.DELIVERY_DEADLINE_EXCEEDED);
  }

  @Test
  void 시도_상한에_도달하면_시도_초과로_종결한다() {
    // 이미 5번 시도한 작업이다. 이번 선점으로 6번째가 되어 상한에 닿는다.
    MailOutboxEntry entry =
        withId(
            MailOutboxEntry.enqueue(
                AccountActionPurpose.EMAIL_VERIFICATION, EMAIL, now().plusHours(24), now()));
    withAttemptCount(entry, 5);
    this.outbox.candidates.add(entry);
    activeUser(null);
    this.provider.willReturn(MailProvider.SendResult.RETRYABLE);

    this.worker.processOnce(10);

    assertThat(this.outbox.terminalStatus).isEqualTo(MailDispatchStatus.FAILED);
    assertThat(this.outbox.terminalFailureKind)
        .isEqualTo(MailDispatchFailureKinds.MAX_ATTEMPTS_EXCEEDED);
  }

  @Test
  void 선점에_실패한_작업은_처리하지_않는다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    this.outbox.claimSucceeds = false;

    assertThat(this.worker.processOnce(10)).isZero();
    assertThat(this.provider.sendCount()).isZero();
    assertThat(this.outbox.terminalStatus).isNull();
  }

  @Test
  void 후보_조회에_정책의_최대_시도_횟수와_batch_크기를_순서대로_넘긴다() {
    // 두 인자는 모두 int라 순서가 뒤바뀌어도 컴파일된다. 정책값 6과 다른 batch 크기를 써야 뒤바뀜이 드러난다.
    int batchSize = 3;

    this.worker.processOnce(batchSize);

    assertThat(this.outbox.candidateQueries).containsExactly(new CandidateQuery(6, batchSize));
  }

  // ---------- 요청 식별자와 로그 문맥 (API 명세 3.8절) ----------

  @Test
  void batch_크기는_1_이상이어야_한다() {
    assertThatThrownBy(() -> this.worker.processOnce(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 작업마다_다른_요청_식별자로_처리한다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    pendingJob(AccountActionPurpose.PASSWORD_RESET);
    activeUser(null);

    assertThat(this.worker.processOnce(10)).isEqualTo(2);

    // 제공자 호출 두 번이 서로 다른 식별자 아래에서 일어났다.
    List<String> callRequestIds =
        this.provider.sent().stream().map(RecordingMailProvider.Sent::requestId).toList();
    assertThat(callRequestIds).hasSize(2).doesNotContainNull().doesNotHaveDuplicates();
    // 상태 전이 로그도 같은 식별자를 그대로 쓴다.
    assertThat(loggedRequestIds()).containsExactlyInAnyOrderElementsOf(callRequestIds);
  }

  @Test
  void 선점하지_못한_후보에는_처리_시도_식별자를_만들지_않는다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    this.outbox.claimSucceeds = false;

    this.worker.processOnce(10);

    assertThat(this.logs.list).isNotEmpty();
    assertThat(loggedRequestIds()).containsOnlyNulls();
  }

  @Test
  void 정리도_자기_요청_식별자로_실행한다() {
    this.outbox.failStaleResult = 1;

    this.worker.cleanUp();

    assertThat(loggedRequestIds()).hasSize(1).doesNotContainNull();
    assertThat(MDC.get(REQUEST_ID)).isNull();
  }

  @Test
  void 처리_뒤_MDC에_요청_식별자가_남지_않는다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);

    this.worker.processOnce(10);
    this.worker.cleanUp();

    // 스레드 풀이 스레드를 재사용하므로 남으면 다음 작업 로그에 남의 식별자가 붙는다.
    assertThat(MDC.get(REQUEST_ID)).isNull();
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }

  @Test
  void 이미_있던_요청_식별자는_처리_뒤_되돌린다() {
    MDC.put(REQUEST_ID, "caller-context");
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);

    this.worker.processOnce(10);
    this.worker.cleanUp();

    assertThat(MDC.get(REQUEST_ID)).isEqualTo("caller-context");
    // 그 문맥을 빌려 쓰지도 않았다. 처리 로그는 자기 식별자를 썼다.
    assertThat(loggedRequestIds()).doesNotContain("caller-context").doesNotContainNull();
  }

  // ---------- 예외 격리 ----------

  @Test
  void 한_작업의_예외가_다음_작업을_막지_않는다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    pendingJob(AccountActionPurpose.PASSWORD_RESET);
    activeUser(null);
    AtomicInteger calls = new AtomicInteger();
    this.provider.willThrow(
        message -> calls.incrementAndGet() == 1 ? new IllegalStateException("boom") : null);

    assertThat(this.worker.processOnce(10)).isEqualTo(2);

    // 두 작업 모두 제공자까지 갔다. 첫 작업의 예외가 batch를 끊지 않았다.
    assertThat(this.provider.sendCount()).isEqualTo(2);
    assertThat(this.outbox.terminalStatus).isEqualTo(MailDispatchStatus.PROVIDER_ACCEPTED);
  }

  @Test
  void 예외가_나도_MDC가_다음_작업으로_새지_않는다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    pendingJob(AccountActionPurpose.PASSWORD_RESET);
    activeUser(null);
    this.provider.willThrow(message -> new IllegalStateException("boom"));

    this.worker.processOnce(10);

    assertThat(loggedRequestIds()).hasSize(2).doesNotContainNull().doesNotHaveDuplicates();
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }

  @Test
  void 예외_로그에는_안정된_필드만_남긴다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    activeUser(null);
    // 실제 제공자 client 예외에는 수신 주소나 토큰이 들어갈 수 있다.
    this.provider.willThrow(message -> new IllegalStateException("smtp failed for " + EMAIL));

    this.worker.processOnce(10);

    assertThat(this.logs.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.ERROR);
              String message = event.getFormattedMessage();
              // 남기는 것: requestId(MDC)·outboxId·stage·failureKind
              assertThat(event.getMDCPropertyMap().get(REQUEST_ID)).isNotBlank();
              assertThat(message)
                  .contains("outboxId=")
                  .contains("stage=PROVIDER_CALL")
                  .contains("failureKind=UNEXPECTED");
              // 남기지 않는 것: 예외 message·예외 클래스명·수신 주소·스택트레이스
              assertThat(message)
                  .doesNotContain(EMAIL)
                  .doesNotContain("smtp failed")
                  .doesNotContain(IllegalStateException.class.getName())
                  .doesNotContain(IllegalStateException.class.getSimpleName())
                  .doesNotContain("Exception");
              assertThat(event.getThrowableProxy()).isNull();
            });
  }

  @Test
  void 예외_로그의_stage가_실패한_단계를_가리킨다() {
    pendingJob(AccountActionPurpose.EMAIL_VERIFICATION);
    // 사용자 조회에서 터지면 적격성 판정 단계다.
    this.users.failure = new IllegalStateException("db down for " + EMAIL);

    this.worker.processOnce(10);

    assertThat(this.logs.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getFormattedMessage())
                  .contains("stage=ELIGIBILITY")
                  .contains("failureKind=UNEXPECTED")
                  .doesNotContain(EMAIL)
                  .doesNotContain("db down");
              assertThat(event.getThrowableProxy()).isNull();
            });
  }

  @Test
  void 정리는_기한_시도_초과_종결과_보존_기간_삭제를_함께_수행한다() {
    this.worker.cleanUp();

    assertThat(this.outbox.failStaleCalls).isEqualTo(1);
    assertThat(this.outbox.deleteThreshold).isEqualTo(now().minusDays(7));
  }

  // ---------- 테스트 stub ----------

  /** 계약 기본값을 그대로 쓰되 지터를 끄고 결정적으로 만든다. */
  private static final class FixedPolicy implements MailDispatchPolicy {
    @Override
    public int maxAttempts() {
      return 6;
    }

    @Override
    public Duration backoffFor(int attemptCount) {
      return Duration.ofSeconds(30);
    }

    @Override
    public double jitterRatio() {
      return 0;
    }

    @Override
    public Duration visibilityTimeout() {
      return Duration.ofMinutes(5);
    }

    @Override
    public Duration terminalRetention() {
      return Duration.ofDays(7);
    }
  }

  /** 후보 조회가 받은 인자. 이름 붙은 값으로 남겨 두 int 인자의 순서를 구분한다. */
  private record CandidateQuery(int maxAttempts, int limit) {}

  private static final class RecordingMailOutboxRepository implements MailOutboxRepository {
    private final List<MailOutboxEntry> candidates = new ArrayList<>();
    private final List<CandidateQuery> candidateQueries = new ArrayList<>();
    private boolean claimSucceeds = true;
    private MailDispatchStatus terminalStatus;
    private String terminalFailureKind;
    private LocalDateTime retriedAt;
    private String retryFailureKind;
    private int failStaleCalls;
    private int failStaleResult;
    private LocalDateTime deleteThreshold;

    @Override
    public MailOutboxEntry save(MailOutboxEntry entry) {
      return entry;
    }

    @Override
    public List<MailOutboxEntry> findClaimCandidates(
        LocalDateTime now, int maxAttempts, int limit) {
      this.candidateQueries.add(new CandidateQuery(maxAttempts, limit));
      return List.copyOf(this.candidates);
    }

    @Override
    public boolean claim(MailOutboxClaim claim, LocalDateTime now, int maxAttempts) {
      return this.claimSucceeds;
    }

    @Override
    public boolean markRetry(
        MailOutboxClaim claim,
        LocalDateTime nextAttemptAt,
        String lastErrorKind,
        LocalDateTime now) {
      this.retriedAt = nextAttemptAt;
      this.retryFailureKind = lastErrorKind;
      return true;
    }

    @Override
    public boolean markTerminal(
        MailOutboxClaim claim, MailDispatchStatus status, String lastErrorKind, LocalDateTime now) {
      this.terminalStatus = status;
      this.terminalFailureKind = lastErrorKind;
      return true;
    }

    @Override
    public int failStale(LocalDateTime now, int maxAttempts) {
      this.failStaleCalls++;
      return this.failStaleResult;
    }

    @Override
    public int deleteTerminalCompletedBefore(LocalDateTime threshold) {
      this.deleteThreshold = threshold;
      return 0;
    }
  }

  private static final class StubUserRepository implements UserRepository {
    private User user;

    /** 적격성 판정 단계에서 예상하지 못한 예외가 나는 상황을 만든다. */
    private RuntimeException failure;

    @Override
    public User save(User user) {
      return user;
    }

    @Override
    public Optional<User> findById(Long id) {
      return Optional.ofNullable(this.user);
    }

    @Override
    public Optional<User> findByUsername(String username) {
      return Optional.ofNullable(this.user);
    }

    @Override
    public Optional<User> findByEmail(Email email) {
      if (this.failure != null) {
        throw this.failure;
      }
      return Optional.ofNullable(this.user);
    }

    @Override
    public boolean existsByUsername(String username) {
      return this.user != null;
    }

    @Override
    public boolean existsByEmail(Email email) {
      return this.user != null;
    }

    @Override
    public int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
      throw new UnsupportedOperationException("worker는 이메일 인증을 완료하지 않는다.");
    }

    @Override
    public int recordLoginFailure(
        long userId, int maxAttempts, LocalDateTime lockedUntil, LocalDateTime now) {
      throw new UnsupportedOperationException("worker는 로그인 상태를 바꾸지 않는다.");
    }

    @Override
    public int recordLoginSuccess(
        long userId, long expectedRowVersion, long expectedAuthVersion, LocalDateTime now) {
      throw new UnsupportedOperationException("worker는 로그인 상태를 바꾸지 않는다.");
    }

    @Override
    public int resetPassword(
        long userId,
        long expectedRowVersion,
        String expectedPasswordHash,
        long expectedAuthVersion,
        String newPasswordHash,
        LocalDateTime now) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class RecordingActionTokenStore implements ActionTokenStore {
    private final Deque<ActionTokenStore.IssueResult> queued = new ArrayDeque<>();
    private ActionTokenStore.IssueResult result = ActionTokenStore.IssueResult.ISSUED;
    private int issued;

    private void queue(ActionTokenStore.IssueResult... results) {
      this.queued.addAll(List.of(results));
    }

    @Override
    public ActionTokenStore.IssueResult issueActionToken(ActionTokenStore.TokenRecord token) {
      this.issued++;
      return this.queued.isEmpty() ? this.result : this.queued.pollFirst();
    }

    @Override
    public ActionTokenStore.LookupResult lookup(ActionTokenStore.TokenRecord submitted) {
      return ActionTokenStore.LookupResult.ACTIVE;
    }

    @Override
    public ActionTokenStore.ConsumeResult consume(ActionTokenStore.TokenRecord submitted) {
      return ActionTokenStore.ConsumeResult.CONSUMED;
    }
  }

  /** 실제 서명을 만들지 않는다. worker가 보는 것은 발급 결과의 형태뿐이며, 발급마다 다른 값이어야 한다. */
  private static final class StubActionTokenIssuer
      implements EmailVerificationTokenIssuer, PasswordResetTokenIssuer {
    private final AccountActionPurpose purpose;
    private final AtomicInteger sequence = new AtomicInteger();
    private final List<IssuedActionToken> issued = new ArrayList<>();

    private StubActionTokenIssuer(AccountActionPurpose purpose) {
      this.purpose = purpose;
    }

    @Override
    public IssuedActionToken issue(ActionTokenClaims claims) {
      int index = this.sequence.incrementAndGet();
      IssuedActionToken token =
          new IssuedActionToken(
              "header.payload.signature-" + index,
              // 계약 형식(43자)을 지키면서 발급마다 다른 값.
              String.format("%043d", index),
              UUID.randomUUID().toString(),
              this.purpose,
              NOW.getEpochSecond(),
              NOW.getEpochSecond() + 3600);
      this.issued.add(token);
      return token;
    }

    private int issuedCount() {
      return this.issued.size();
    }

    private IssuedActionToken issuedAt(int index) {
      return this.issued.get(index);
    }

    private List<IssuedActionToken> issued() {
      return List.copyOf(this.issued);
    }
  }
}
