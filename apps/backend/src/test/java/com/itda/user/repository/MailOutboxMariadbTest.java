package com.itda.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.MailDispatchFailureKinds;
import com.itda.user.domain.MailDispatchStatus;
import com.itda.user.domain.MailOutboxClaim;
import com.itda.user.domain.MailOutboxEntry;
import jakarta.persistence.Table;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영과 같은 MariaDB 11.8.9에 대고 {@code mail_outbox}를 검증한다 (ERD 3.5절).
 *
 * <p>H2로는 증명되지 않는 것만 여기서 본다 — CHECK 네 개가 실제로 만들어지는지, 이름이 Entity 정의와 정확히 같은지, 위반 행을 DB가 거부하는지,
 * {@code DATETIME(6)}이 마이크로초를 그대로 보존해 조건부 선점의 시각 일치가 성립하는지, 실제 동시 UPDATE 경쟁에서 하나만 선점하는지, 가시성이 지난
 * 작업을 회수할 수 있는지. 나머지 조건 절 논리는 H2 테스트가 이미 고정한다.
 *
 * <p><b>테스트 트랜잭션을 쓰지 않는다.</b> 동시 선점을 보려면 각 스레드가 자기 커넥션과 자기 트랜잭션을 써야 하는데, 테스트 트랜잭션 안에 갇히면 다른 스레드가 그
 * 행을 보지 못한다. 그래서 각 테스트가 시작할 때 자기 데이터를 직접 지운다.
 *
 * <p>{@code @Tag("mariadb")}가 붙어 기본 {@code test} task에서는 제외되고 {@code mariadbTest}에서만 실행된다. 접속 정보는
 * 환경변수로 받으며 비밀번호 기본값은 없다.
 */
@Tag("mariadb")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(MailOutboxMariadbTest.RepositoryUnderTest.class)
class MailOutboxMariadbTest {

  private static final String EMAIL = "learner@example.com";
  private static final int MAX_ATTEMPTS = 6;

  /** ERD 3.5절이 요구하는 네 CHECK의 계약 이름. */
  private static final List<String> CONTRACT_CONSTRAINTS =
      List.of(
          "ck_mail_outbox_attempt_count",
          "ck_mail_outbox_delivery_deadline",
          "ck_mail_outbox_pending_state",
          "ck_mail_outbox_terminal_state");

  @TestConfiguration(proxyBeanMethods = false)
  static class RepositoryUnderTest {
    @Bean
    MailOutboxRepositoryImpl mailOutboxRepository(MailOutboxJpaRepository jpaRepository) {
      return new MailOutboxRepositoryImpl(jpaRepository);
    }
  }

  @Autowired private MailOutboxRepositoryImpl repository;
  @Autowired private MailOutboxJpaRepository jpaRepository;
  @Autowired private DataSource dataSource;

  private JdbcTemplate jdbc;
  private LocalDateTime now;

  @BeforeEach
  void setUp() {
    this.jdbc = new JdbcTemplate(this.dataSource);
    this.jdbc.execute("delete from mail_outbox");
    this.now = MailOutboxEntry.truncate(LocalDateTime.of(2026, 9, 13, 9, 0));
  }

  private MailOutboxEntry pending(AccountActionPurpose purpose, Duration deadlineAfter) {
    return this.repository.save(
        MailOutboxEntry.enqueue(purpose, EMAIL, this.now.plus(deadlineAfter), this.now));
  }

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

  private Map<String, Object> row(Long id) {
    return this.jdbc.queryForMap("select * from mail_outbox where id = ?", id);
  }

  // ---------- 환경 ----------

  @Test
  void 실제_DB가_MariaDB_11_8_9다() {
    // 이 테스트가 H2나 다른 버전에 붙어 통과하는 일이 없어야 한다.
    String version = this.jdbc.queryForObject("select version()", String.class);

    assertThat(version).startsWith("11.8.9-MariaDB");
  }

  // ---------- CHECK 제약 ----------

  @Test
  void ERD가_요구하는_CHECK_네_개가_Entity_정의와_같은_이름으로_생성된다() {
    List<String> declared =
        Arrays.stream(MailOutboxEntry.class.getAnnotation(Table.class).check())
            .map(check -> check.name().toLowerCase(Locale.ROOT))
            .sorted()
            .toList();
    List<String> created =
        this.jdbc
            .queryForList(
                """
                select constraint_name from information_schema.check_constraints
                 where constraint_schema = database() and table_name = 'mail_outbox'
                """,
                String.class)
            .stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .sorted()
            .toList();

    // 계약 이름 = Entity 선언 = 실제 DB. 셋이 정확히 같아야 한다.
    assertThat(declared).containsExactlyElementsOf(CONTRACT_CONSTRAINTS.stream().sorted().toList());
    assertThat(created).containsExactlyElementsOf(declared);
  }

  @Test
  void 음수_시도_횟수를_DB가_거부한다() {
    assertRejectedBy(
        "ck_mail_outbox_attempt_count",
        () ->
            insertPending(
                "'EMAIL_VERIFICATION', 'x@example.com', 'PENDING', -1,"
                    + " '2026-09-13 09:00:00', '2026-09-14 09:00:00',"
                    + " '2026-09-13 09:00:00', '2026-09-13 09:00:00'"));
  }

  @Test
  void 접수_시각_이하의_기한을_DB가_거부한다() {
    assertRejectedBy(
        "ck_mail_outbox_delivery_deadline",
        () ->
            insertPending(
                "'PASSWORD_RESET', 'x@example.com', 'PENDING', 0,"
                    + " '2026-09-13 09:00:00', '2026-09-13 09:00:00',"
                    + " '2026-09-13 09:00:00', '2026-09-13 09:00:00'"));
  }

  @Test
  void 수신_주소가_없는_PENDING_행을_DB가_거부한다() {
    assertRejectedBy(
        "ck_mail_outbox_pending_state",
        () ->
            insertPending(
                "'EMAIL_VERIFICATION', null, 'PENDING', 0,"
                    + " '2026-09-13 09:00:00', '2026-09-14 09:00:00',"
                    + " '2026-09-13 09:00:00', '2026-09-13 09:00:00'"));
  }

  @Test
  void 종결_시각이_없는_종결_행을_DB가_거부한다() {
    assertRejectedBy(
        "ck_mail_outbox_terminal_state",
        () ->
            insertTerminal(
                "'PASSWORD_RESET', null, 'FAILED', 6,"
                    + " null, '2026-09-14 09:00:00', null,"
                    + " '2026-09-13 09:00:00', '2026-09-13 09:10:00'"));
  }

  @Test
  void 종결로_바꾸면서_수신_주소를_남기는_UPDATE를_DB가_거부한다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));

    // 실제 전이 경로는 엔티티가 아니라 조건부 UPDATE다. 그 경로도 막혀야 한다.
    assertRejectedBy(
        "ck_mail_outbox_terminal_state",
        () ->
            this.jdbc.update(
                """
                update mail_outbox
                   set status = 'FAILED', completed_at = '2026-09-13 09:10:00'
                 where id = ?
                """,
                entry.getId()));
  }

  // ---------- DATETIME(6) 정밀도 ----------

  @Test
  void 시각_컬럼이_DATETIME_6이다() {
    Map<String, String> types =
        this.jdbc
            .queryForList(
                """
                select column_name, column_type from information_schema.columns
                 where table_schema = database() and table_name = 'mail_outbox'
                   and column_name in ('next_attempt_at','delivery_deadline_at',
                                       'completed_at','created_at','updated_at')
                """)
            .stream()
            .collect(
                Collectors.toMap(
                    r -> String.valueOf(r.get("column_name")).toLowerCase(Locale.ROOT),
                    r -> String.valueOf(r.get("column_type")).toLowerCase(Locale.ROOT)));

    assertThat(types).hasSize(5);
    assertThat(types.values()).containsOnly("datetime(6)");
  }

  @Test
  void 마이크로초가_그대로_저장되고_그대로_선점_조건에_걸린다() {
    // 정밀도가 깎이면 조건부 선점의 시각 일치가 깨진다. 계약이 절삭 규칙을 못박은 이유다.
    LocalDateTime precise = this.now.withNano(123_456_000);
    MailOutboxEntry entry =
        this.repository.save(
            MailOutboxEntry.enqueue(
                AccountActionPurpose.EMAIL_VERIFICATION, EMAIL, precise.plusHours(24), precise));

    // 소수부만 DB 함수로 직접 읽는다. 값 비교는 driver의 시간대 변환에 걸리지만 마이크로초 보존 여부는 그와 무관하다.
    Integer microseconds =
        this.jdbc.queryForObject(
            "select microsecond(next_attempt_at) from mail_outbox where id = ?",
            Integer.class,
            entry.getId());
    assertThat(microseconds).isEqualTo(123_456);

    // 애플리케이션이 실제로 쓰는 경로(JPA)로 되읽으면 쓴 값 그대로여야 한다.
    MailOutboxEntry reloaded = this.jpaRepository.findById(entry.getId()).orElseThrow();
    assertThat(reloaded.getNextAttemptAt()).isEqualTo(precise);

    // 그리고 그 값을 그대로 조건에 걸면 선점이 성립한다.
    MailOutboxClaim claim =
        new MailOutboxClaim(
            entry.getId(),
            entry.getPurpose(),
            EMAIL,
            1,
            reloaded.getNextAttemptAt(),
            MailOutboxEntry.truncate(precise.plusMinutes(5)),
            entry.getDeliveryDeadlineAt());
    assertThat(this.repository.claim(claim, precise, MAX_ATTEMPTS)).isTrue();
  }

  @Test
  void 마이크로초가_다르면_선점_조건이_어긋난다() {
    LocalDateTime precise = this.now.withNano(123_456_000);
    MailOutboxEntry entry =
        this.repository.save(
            MailOutboxEntry.enqueue(
                AccountActionPurpose.EMAIL_VERIFICATION, EMAIL, precise.plusHours(24), precise));

    // 1마이크로초만 달라도 다른 값이다. 정밀도가 초 단위로 깎였다면 이 선점이 성공해 버린다.
    MailOutboxClaim stale =
        new MailOutboxClaim(
            entry.getId(),
            entry.getPurpose(),
            EMAIL,
            1,
            precise.withNano(123_457_000),
            MailOutboxEntry.truncate(precise.plusMinutes(5)),
            entry.getDeliveryDeadlineAt());

    assertThat(this.repository.claim(stale, precise, MAX_ATTEMPTS)).isFalse();
  }

  // ---------- 선점 경쟁과 회수 ----------

  @Test
  void 여러_worker가_같은_행을_동시에_선점해도_하나만_성공한다() throws Exception {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    int workers = 8;
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Boolean>> claims =
        IntStream.range(0, workers)
            .<Callable<Boolean>>mapToObj(
                i ->
                    () -> {
                      start.await();
                      return this.repository.claim(
                          claimOf(entry, Duration.ofMinutes(5)), this.now, MAX_ATTEMPTS);
                    })
            .toList();

    List<Boolean> results = runConcurrently(claims, start);

    assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
    assertThat(row(entry.getId()).get("attempt_count")).isEqualTo(1);
  }

  @Test
  void 가시성이_지나면_다른_worker가_회수해_다시_선점한다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    MailOutboxClaim first = claimOf(entry, Duration.ofMinutes(5));
    assertThat(this.repository.claim(first, this.now, MAX_ATTEMPTS)).isTrue();

    // 가시성 안에서는 후보가 아니다.
    assertThat(this.repository.findClaimCandidates(this.now.plusMinutes(1), MAX_ATTEMPTS, 10))
        .isEmpty();

    // 만료 뒤에는 다시 후보가 되고, 회수한 worker가 선점한다.
    List<MailOutboxEntry> candidates =
        this.repository.findClaimCandidates(this.now.plusMinutes(5), MAX_ATTEMPTS, 10);
    assertThat(candidates).hasSize(1);
    MailOutboxEntry reclaimed = candidates.get(0);
    MailOutboxClaim second =
        new MailOutboxClaim(
            reclaimed.getId(),
            reclaimed.getPurpose(),
            reclaimed.getRecipientEmail(),
            reclaimed.getAttemptCount() + 1,
            reclaimed.getNextAttemptAt(),
            MailOutboxEntry.truncate(this.now.plusMinutes(10)),
            reclaimed.getDeliveryDeadlineAt());
    assertThat(this.repository.claim(second, this.now.plusMinutes(5), MAX_ATTEMPTS)).isTrue();

    // 앞선 worker의 늦은 갱신은 0행으로 실패한다.
    assertThat(
            this.repository.markRetry(
                first,
                this.now.plusMinutes(20),
                MailDispatchFailureKinds.PROVIDER_RETRYABLE,
                this.now.plusMinutes(6)))
        .isFalse();
    assertThat(row(entry.getId()).get("attempt_count")).isEqualTo(2);
  }

  @Test
  void 중단된_작업을_기한_초과와_시도_초과로_회수해_종결한다() {
    MailOutboxEntry expired = pending(AccountActionPurpose.PASSWORD_RESET, Duration.ofMinutes(30));
    MailOutboxEntry exhausted =
        pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofDays(1));
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      MailOutboxEntry candidate =
          this.repository
              .findClaimCandidates(this.now.plusMinutes(attempt * 5L), MAX_ATTEMPTS, 10)
              .stream()
              .filter(c -> c.getId().equals(exhausted.getId()))
              .findFirst()
              .orElseThrow();
      MailOutboxClaim claim =
          new MailOutboxClaim(
              candidate.getId(),
              candidate.getPurpose(),
              candidate.getRecipientEmail(),
              candidate.getAttemptCount() + 1,
              candidate.getNextAttemptAt(),
              MailOutboxEntry.truncate(this.now.plusMinutes(attempt * 5L + 5)),
              candidate.getDeliveryDeadlineAt());
      assertThat(this.repository.claim(claim, this.now.plusMinutes(attempt * 5L), MAX_ATTEMPTS))
          .isTrue();
    }

    assertThat(this.repository.failStale(this.now.plusHours(2), MAX_ATTEMPTS)).isEqualTo(2);

    assertThat(row(expired.getId()))
        .containsEntry("status", "FAILED")
        .containsEntry("last_error_kind", MailDispatchFailureKinds.DELIVERY_DEADLINE_EXCEEDED)
        .containsEntry("recipient_email", null)
        .containsEntry("next_attempt_at", null);
    assertThat(row(exhausted.getId()))
        .containsEntry("status", "FAILED")
        .containsEntry("last_error_kind", MailDispatchFailureKinds.MAX_ATTEMPTS_EXCEEDED);
  }

  // ---------- 상태 전이 ----------

  @Test
  void 재시도는_수신_주소를_남기고_최신_실패_원인으로_덮어쓴다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
    MailOutboxClaim claim = claimOf(entry, Duration.ofMinutes(5));
    this.repository.claim(claim, this.now, MAX_ATTEMPTS);

    assertThat(
            this.repository.markRetry(
                claim,
                this.now.plusSeconds(30),
                MailDispatchFailureKinds.PROVIDER_AUTH_CONFIG_ERROR,
                this.now))
        .isTrue();

    assertThat(row(entry.getId()))
        .containsEntry("status", "PENDING")
        .containsEntry("recipient_email", EMAIL)
        .containsEntry("last_error_kind", MailDispatchFailureKinds.PROVIDER_AUTH_CONFIG_ERROR)
        .containsEntry("completed_at", null);
  }

  @Test
  void 접수_종결은_수신_주소를_지우고_실패_분류를_남기지_않는다() {
    MailOutboxEntry entry = pending(AccountActionPurpose.PASSWORD_RESET, Duration.ofMinutes(30));
    MailOutboxClaim claim = claimOf(entry, Duration.ofMinutes(5));
    this.repository.claim(claim, this.now, MAX_ATTEMPTS);
    this.repository.markRetry(
        claim, this.now.plusSeconds(30), MailDispatchFailureKinds.PROVIDER_RETRYABLE, this.now);

    MailOutboxEntry reclaimed =
        this.repository.findClaimCandidates(this.now.plusMinutes(1), MAX_ATTEMPTS, 10).get(0);
    MailOutboxClaim second =
        new MailOutboxClaim(
            reclaimed.getId(),
            reclaimed.getPurpose(),
            reclaimed.getRecipientEmail(),
            reclaimed.getAttemptCount() + 1,
            reclaimed.getNextAttemptAt(),
            MailOutboxEntry.truncate(this.now.plusMinutes(6)),
            reclaimed.getDeliveryDeadlineAt());
    this.repository.claim(second, this.now.plusMinutes(1), MAX_ATTEMPTS);

    assertThat(
            this.repository.markTerminal(
                second, MailDispatchStatus.PROVIDER_ACCEPTED, null, this.now.plusMinutes(1)))
        .isTrue();

    assertThat(row(entry.getId()))
        .containsEntry("status", "PROVIDER_ACCEPTED")
        .containsEntry("recipient_email", null)
        .containsEntry("next_attempt_at", null)
        // 재시도에서 남았던 분류는 성공 종결에서 정리된다 (ERD 3.5절).
        .containsEntry("last_error_kind", null);
    assertThat(row(entry.getId()).get("completed_at")).isNotNull();
  }

  // ---------- 도우미 ----------

  private void insertPending(String values) {
    this.jdbc.update(
        "insert into mail_outbox (purpose, recipient_email, status, attempt_count,"
            + " next_attempt_at, delivery_deadline_at, created_at, updated_at)"
            + " values ("
            + values
            + ")");
  }

  private void insertTerminal(String values) {
    this.jdbc.update(
        "insert into mail_outbox (purpose, recipient_email, status, attempt_count,"
            + " next_attempt_at, delivery_deadline_at, completed_at, created_at, updated_at)"
            + " values ("
            + values
            + ")");
  }

  /** 어느 제약이 막았는지까지 확인한다. "예외가 났다"만 보면 SQL 오타로도 통과한다. */
  private void assertRejectedBy(String constraintName, Runnable statement) {
    Throwable thrown = catchThrowable(statement::run);

    assertThat(thrown).as("%s가 막지 않았습니다.", constraintName).isNotNull();
    StringWriter trace = new StringWriter();
    thrown.printStackTrace(new PrintWriter(trace));
    assertThat(trace.toString().toLowerCase(Locale.ROOT)).contains(constraintName);
  }

  private static <T> List<T> runConcurrently(List<Callable<T>> tasks, CountDownLatch start)
      throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
    try {
      List<Future<T>> futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      executor.shutdownNow();
    }
  }
}
