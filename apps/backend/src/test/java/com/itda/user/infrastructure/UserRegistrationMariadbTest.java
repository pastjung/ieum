package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.support.password.RecordingCompromisedPasswordChecker;
import com.itda.support.password.RecordingPasswordHasher;
import com.itda.user.application.MailDispatchPolicy;
import com.itda.user.application.MailOutboxEnqueueService;
import com.itda.user.application.NewPasswordService;
import com.itda.user.application.UserRegistrationCommand;
import com.itda.user.application.UserRegistrationService;
import com.itda.user.application.UserSummary;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.MailOutboxEntry;
import com.itda.user.domain.MailOutboxRepository;
import com.itda.user.domain.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영과 같은 MariaDB 11.8.9에 대고 회원가입 트랜잭션을 검증한다 (API 명세 3.2.1절, ERD 3.1·3.5절).
 *
 * <p>H2로는 증명되지 않는 것만 여기서 본다 — {@code users}와 {@code mail_outbox}가 정말 한 트랜잭션으로 커밋·롤백되는지, UNIQUE 제약이
 * Entity 선언과 같은 이름으로 실제 생성되는지, 대소문자만 다른 아이디를 DB가 막는지, 진짜 동시 INSERT 경쟁에서 하나만 살아남는지, 그리고 그때 나오는 예외가
 * {@code 409}와 {@code 503}으로 제대로 갈라지는지.
 *
 * <p><b>테스트 트랜잭션을 쓰지 않는다.</b> 커밋 여부와 동시 경쟁이 검증 대상이라 각 호출이 자기 트랜잭션을 열어야 한다. 대신 각 테스트가 시작할 때 자기 데이터를
 * 직접 지운다.
 *
 * <p>유출 조회와 Argon2id만 테스트 대역이다. 외부 HIBP를 부르지 않고, 해시 비용으로 동시성 테스트가 느려지지 않게 하기 위해서다. 검증 대상인 트랜잭션
 * 경계·제약·예외 분류는 전부 운영과 같은 코드다.
 */
@Tag("mariadb")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(UserRegistrationMariadbTest.ServiceUnderTest.class)
class UserRegistrationMariadbTest {

  private static final String USERNAME = "learner01";
  private static final String EMAIL = "learner01@example.com";
  private static final String PASSWORD = "충분히 긴 새 비밀번호 문구";
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 13, 9, 0);

  @TestConfiguration(proxyBeanMethods = false)
  static class ServiceUnderTest {

    @Bean
    MailDispatchPolicy mailDispatchPolicy() {
      return new MailDispatchProperties(
          6,
          List.of(Duration.ofSeconds(30)),
          Duration.ofMinutes(10),
          0.2,
          Duration.ofSeconds(60),
          Duration.ofMinutes(5),
          Duration.ofDays(7));
    }

    @Bean
    MailOutboxRepository mailOutboxRepository(
        MailOutboxJpaRepository jpaRepository, MailDispatchPolicy policy) {
      return new MailOutboxRepositoryImpl(jpaRepository, policy);
    }

    @Bean
    UserRepository userRepository(UserJpaRepository jpaRepository) {
      return new UserRepositoryImpl(jpaRepository);
    }

    @Bean
    MailOutboxEnqueueService mailOutboxEnqueueService(MailOutboxRepository mailOutboxRepository) {
      return new MailOutboxEnqueueService(mailOutboxRepository);
    }

    @Bean
    RecordingCompromisedPasswordChecker compromisedPasswordChecker() {
      return new RecordingCompromisedPasswordChecker();
    }

    @Bean
    RecordingPasswordHasher passwordHasher() {
      return new RecordingPasswordHasher();
    }

    @Bean
    NewPasswordService newPasswordService(
        RecordingCompromisedPasswordChecker checker, RecordingPasswordHasher hasher) {
      return new NewPasswordService(checker, hasher);
    }

    @Bean
    TransactionOperations userRegistrationTransactionOperations(PlatformTransactionManager tm) {
      return new TransactionTemplate(tm);
    }

    @Bean
    UserRegistrationService userRegistrationService(
        UserRepository userRepository,
        MailOutboxEnqueueService mailOutboxEnqueueService,
        NewPasswordService newPasswordService,
        TransactionOperations userRegistrationTransactionOperations) {
      return new UserRegistrationService(
          userRepository,
          mailOutboxEnqueueService,
          newPasswordService,
          userRegistrationTransactionOperations,
          Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }
  }

  @Autowired private UserRegistrationService service;
  @Autowired private UserRepository userRepository;
  @Autowired private NewPasswordService newPasswordService;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    this.jdbc = new JdbcTemplate(this.dataSource);
    this.jdbc.execute("delete from mail_outbox");
    this.jdbc.execute("delete from users");
  }

  private static UserRegistrationCommand command(String username, String email) {
    return new UserRegistrationCommand(username, email, PASSWORD);
  }

  private ErrorCode errorCodeOf(UserRegistrationCommand command) {
    return catchThrowableOfType(BusinessException.class, () -> this.service.register(command))
        .getErrorCode();
  }

  private long userCount() {
    return this.jdbc.queryForObject("select count(*) from users", Long.class);
  }

  private long outboxCount() {
    return this.jdbc.queryForObject("select count(*) from mail_outbox", Long.class);
  }

  // ---------- 환경 ----------

  @Test
  void 실제_DB가_MariaDB_11_8_9다() {
    assertThat(this.jdbc.queryForObject("select version()", String.class))
        .startsWith("11.8.9-MariaDB");
  }

  @Test
  void username과_email에_단일_컬럼_UNIQUE_인덱스가_하나씩만_있다() {
    Map<String, List<String>> uniqueIndexes = uniqueIndexesOfUsers();

    // 컬럼별로 정확히 하나여야 한다. 둘이면 같은 값을 두 번 검사하는 중복 인덱스가 생긴 것이다 —
    // Entity가 제약 이름을 명시하면 ddl-auto=update가 기존 인덱스를 그대로 둔 채 하나를 더 만든다.
    assertThat(
            uniqueIndexes.values().stream().filter(columns -> columns.equals(List.of("username"))))
        .hasSize(1);
    assertThat(uniqueIndexes.values().stream().filter(columns -> columns.equals(List.of("email"))))
        .hasSize(1);
    assertThat(uniqueIndexes).hasSize(3); // PRIMARY + username + email
  }

  @Test
  void row_version이_bigint_NOT_NULL로_매핑된다() {
    Map<String, Object> column =
        this.jdbc.queryForMap(
            """
            select column_type, is_nullable from information_schema.columns
             where table_schema = database() and table_name = 'users'
               and column_name = 'row_version'
            """);

    assertThat(String.valueOf(column.get("column_type")).toLowerCase(Locale.ROOT))
        .startsWith("bigint");
    assertThat(column).containsEntry("is_nullable", "NO");
  }

  // ---------- 한 트랜잭션 ----------

  @Test
  void 가입은_users와_mail_outbox를_함께_커밋한다() {
    UserSummary summary = this.service.register(command(USERNAME, EMAIL));

    // 서비스가 반환한 뒤에는 커밋이 끝나 있다 — 이 조회는 트랜잭션 밖이다.
    Map<String, Object> user =
        this.jdbc.queryForMap("select * from users where id = ?", summary.userId());
    assertThat(user)
        .containsEntry("username", USERNAME)
        .containsEntry("email", EMAIL)
        .containsEntry("role", "LEARNER")
        .containsEntry("status", "ACTIVE")
        .containsEntry("email_verified_at", null)
        .containsEntry("level", null)
        .containsEntry("withdrawn_at", null)
        .containsEntry("failed_login_count", 0)
        .containsEntry("locked_until", null)
        .containsEntry("auth_version", 0L)
        .containsEntry("row_version", 0L);
    assertThat(user.get("created_at")).isEqualTo(user.get("updated_at"));
    // 주입한 UTC Clock의 시각이 그대로 돌아온다. 값 비교는 애플리케이션이 실제로 쓰는 경로(JPA)로 한다 —
    // 이 저장소의 시각 컬럼은 driver가 JVM 기본 시간대로 변환해 저장하므로 raw 문자열 비교는 계약이 아니라 환경을 본다.
    assertThat(this.userRepository.findById(summary.userId()).orElseThrow().getCreatedAt())
        .isEqualTo(NOW);

    Map<String, Object> outbox = this.jdbc.queryForMap("select * from mail_outbox");
    assertThat(outbox)
        .containsEntry("purpose", AccountActionPurpose.EMAIL_VERIFICATION.name())
        .containsEntry("recipient_email", EMAIL)
        .containsEntry("status", "PENDING")
        .containsEntry("attempt_count", 0);
  }

  @Test
  void 이메일은_정규화한_값으로_저장되고_같은_값이_outbox에_들어간다() {
    this.service.register(command("  " + USERNAME + " ", "  " + EMAIL.toUpperCase(Locale.ROOT)));

    assertThat(this.jdbc.queryForObject("select email from users", String.class)).isEqualTo(EMAIL);
    assertThat(this.jdbc.queryForObject("select recipient_email from mail_outbox", String.class))
        .isEqualTo(EMAIL);
  }

  @Test
  void outbox에_비밀번호도_사용자_식별자도_남지_않는다() {
    this.service.register(command(USERNAME, EMAIL));

    String passwordHash = this.jdbc.queryForObject("select password_hash from users", String.class);
    Map<String, Object> outbox = this.jdbc.queryForMap("select * from mail_outbox");

    assertThat(outbox.keySet()).doesNotContain("user_id", "password", "password_hash");
    assertThat(outbox.values())
        .filteredOn(String.class::isInstance)
        .doesNotContain(passwordHash, PASSWORD, USERNAME);
  }

  @Test
  void outbox_INSERT가_실패하면_사용자도_남지_않는다() {
    UserRegistrationService withFailingOutbox = serviceWithOutboxFailure();

    ErrorCode errorCode =
        catchThrowableOfType(
                BusinessException.class, () -> withFailingOutbox.register(command(USERNAME, EMAIL)))
            .getErrorCode();

    // mail_outbox의 CHECK 위반이지 아이디 중복이 아니다. 409로 둔갑하면 쓸 수 있는 아이디를 못 쓴다고 알려 주는 셈이다.
    assertThat(errorCode).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(userCount()).isZero();
    assertThat(outboxCount()).isZero();
  }

  @Test
  void 사용자_INSERT가_실패하면_outbox도_남지_않는다() {
    this.service.register(command(USERNAME, EMAIL));
    this.jdbc.execute("delete from mail_outbox");

    assertThat(errorCodeOf(command(USERNAME, "other-" + EMAIL)))
        .isEqualTo(ErrorCode.ACCOUNT_IDENTIFIER_UNAVAILABLE);

    assertThat(userCount()).isOne();
    assertThat(outboxCount()).isZero();
  }

  // ---------- UNIQUE 충돌 ----------

  @Test
  void 아이디_중복은_ACCOUNT_IDENTIFIER_UNAVAILABLE이다() {
    this.service.register(command(USERNAME, EMAIL));

    assertThat(errorCodeOf(command(USERNAME, "other-" + EMAIL)))
        .isEqualTo(ErrorCode.ACCOUNT_IDENTIFIER_UNAVAILABLE);
  }

  @Test
  void 이메일_중복도_같은_code다() {
    this.service.register(command(USERNAME, EMAIL));

    assertThat(errorCodeOf(command(USERNAME + "x", EMAIL)))
        .isEqualTo(ErrorCode.ACCOUNT_IDENTIFIER_UNAVAILABLE);
  }

  @Test
  void 대소문자만_다른_아이디를_DB가_막는다() {
    this.service.register(command(USERNAME, EMAIL));

    // 애플리케이션은 표기를 그대로 저장한다. 대소문자 비구분 UNIQUE는 DB collation의 몫이다 (ERD 3.1절).
    assertThat(errorCodeOf(command(USERNAME.toUpperCase(Locale.ROOT), "other-" + EMAIL)))
        .isEqualTo(ErrorCode.ACCOUNT_IDENTIFIER_UNAVAILABLE);
    assertThat(userCount()).isOne();
  }

  @Test
  void 제약_이름이_달라도_중복은_409다() {
    // 기존 DB에는 Hibernate가 지은 이름이, 새로 만든 DB에는 다른 이름이 남는다. 어느 쪽이든 판정이 같아야 한다.
    String indexName = usernameUniqueIndexName();
    this.jdbc.execute(
        "alter table users rename index `%s` to `legacy_name_probe`".formatted(indexName));
    try {
      this.service.register(command(USERNAME, EMAIL));

      assertThat(errorCodeOf(command(USERNAME, "other-" + EMAIL)))
          .isEqualTo(ErrorCode.ACCOUNT_IDENTIFIER_UNAVAILABLE);
    } finally {
      this.jdbc.execute(
          "alter table users rename index `legacy_name_probe` to `%s`".formatted(indexName));
    }
  }

  @Test
  void 동시에_같은_아이디로_가입하면_하나만_성공한다() throws Exception {
    int attempts = 6;
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<ErrorCode>> registrations = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      String email = "race" + i + "@example.com";
      registrations.add(
          () -> {
            start.await();
            try {
              this.service.register(command(USERNAME, email));
              return null;
            } catch (BusinessException e) {
              return e.getErrorCode();
            }
          });
    }

    List<ErrorCode> results = runConcurrently(registrations, start);

    // 사전 조회로는 막을 수 없는 틈이다. 최종 권위는 DB UNIQUE이고, 진 쪽은 전부 409여야 한다.
    assertThat(results).filteredOn(Objects::isNull).hasSize(1);
    assertThat(results)
        .filteredOn(Objects::nonNull)
        .containsOnly(ErrorCode.ACCOUNT_IDENTIFIER_UNAVAILABLE);
    assertThat(userCount()).isOne();
    assertThat(outboxCount()).isOne();
  }

  // ---------- 도우미 ----------

  /** 인덱스 이름 → 그 인덱스가 덮는 컬럼 목록. 이름 자체는 비교하지 않는다. */
  private Map<String, List<String>> uniqueIndexesOfUsers() {
    Map<String, List<String>> indexes = new LinkedHashMap<>();
    for (Map<String, Object> row :
        this.jdbc.queryForList(
            """
            select index_name, column_name from information_schema.statistics
             where table_schema = database() and table_name = 'users' and non_unique = 0
             order by index_name, seq_in_index
            """)) {
      indexes
          .computeIfAbsent(String.valueOf(row.get("index_name")), name -> new ArrayList<>())
          .add(String.valueOf(row.get("column_name")).toLowerCase(Locale.ROOT));
    }
    return indexes;
  }

  private String usernameUniqueIndexName() {
    return uniqueIndexesOfUsers().entrySet().stream()
        .filter(entry -> entry.getValue().equals(List.of("username")))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow();
  }

  /**
   * {@code mail_outbox} INSERT만 실패하는 서비스. 나머지 경로는 운영과 같다.
   *
   * <p>기한을 접수 시각보다 앞에 두면 {@code ck_mail_outbox_delivery_deadline}이 막는다 — 애플리케이션 검증이 아니라 DB가 거부하는 진짜
   * 무결성 위반이라 커밋 경계 검증에 쓸 수 있다.
   */
  private UserRegistrationService serviceWithOutboxFailure() {
    MailOutboxEnqueueService failing =
        new MailOutboxEnqueueService(null) {
          @Override
          public MailOutboxEntry enqueue(
              AccountActionPurpose purpose, String recipientEmail, LocalDateTime now) {
            UserRegistrationMariadbTest.this.jdbc.update(
                """
                insert into mail_outbox (purpose, recipient_email, status, attempt_count,
                                         next_attempt_at, delivery_deadline_at,
                                         created_at, updated_at)
                 values (?, ?, 'PENDING', 0, ?, ?, ?, ?)
                """,
                purpose.name(),
                recipientEmail,
                now,
                now.minusHours(1),
                now,
                now);
            return null;
          }
        };
    return new UserRegistrationService(
        this.userRepository,
        failing,
        this.newPasswordService,
        new TransactionTemplate(this.transactionManager),
        Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
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
