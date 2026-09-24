package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.learning.domain.LearningSession;
import com.itda.learning.domain.ProblemAttempt;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.ProblemSkill;
import com.itda.problem.domain.SkillType;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.Email;
import com.itda.user.domain.PasswordPolicy;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.MailOutboxJpaRepository;
import com.itda.user.repository.MailOutboxRepositoryImpl;
import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserJpaRepository;
import com.itda.user.repository.UserRepository;
import com.itda.user.repository.UserRepositoryImpl;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회원 탈퇴의 조건부 UPDATE와 데이터 보존을 운영과 같은 MariaDB 11.8.9에 대고 검증한다 (API 명세 3.2.1·3.6.2절, ERD 3.1절).
 *
 * <p>H2로는 증명되지 않는 것만 여기서 본다 — 한 {@code update versioned} 문장이 탈퇴 상태·가명·폐기 해시·{@code auth_version}·
 * {@code row_version}을 함께 확정하는지, 다섯 조건이 실제 WHERE로 동작하는지, 가명의 실제 UNIQUE 충돌이 rollback 뒤 새 가명으로 이어지는지,
 * 원래 식별자가 즉시 재사용되는지, ERD의 users 참조 컬럼 여섯 개를 가진 다른 도메인의 행과 참조 값이 그대로 남는지.
 *
 * <p>물리 FK가 없으므로 "연결 보존"은 행과 참조 값이 바뀌지 않았는지로 확인한다. 운영 스키마는 바꾸지 않는다. 참조 fixture는 역할 계약을 따른다 — 수업
 * 참여·학습 기록은 LEARNER, 담당 수업·등록 문제는 INSTRUCTOR가 가진다.
 *
 * <p>Argon2id는 운영과 같은 구현이다. Redis만 대역이며 실제 계열 폐기는 Redis 테스트가 맡는다.
 *
 * <p><b>테스트 트랜잭션을 쓰지 않는다.</b> commit 결과가 검증 대상이라 각 호출이 자기 트랜잭션을 열어야 한다.
 */
@Tag("mariadb")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(UserWithdrawalMariadbTest.RepositoryUnderTest.class)
class UserWithdrawalMariadbTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 9, 0);
  private static final LocalDateTime WITHDRAWN_AT = NOW.plusHours(1);
  private static final String CURRENT_PASSWORD = "충분히 긴 현재 비밀번호 문구";
  private static final String OTHER_HASH = "$argon2id$v=19$m=19456,t=2,p=1$other-salt$other-hash";

  private static final Pattern PSEUDONYM_USERNAME = Pattern.compile("^withdrawn-[0-9a-f]{32}$");
  private static final Pattern PSEUDONYM_EMAIL =
      Pattern.compile("^withdrawn-[0-9a-f]{32}@withdrawn\\.invalid$");

  private static final UUID FIRST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SECOND = UUID.fromString("22222222-2222-4222-8222-222222222222");

  /*
   * ERD의 users 참조 컬럼은 여섯 개다. 역할 계약상 한 사용자가 전부 가질 수 없으므로 학습자·강사로 나눠 검증한다.
   * 두 목록을 합치면 ERD 전체와 같아야 한다.
   */

  /** 학습자가 가지는 users 참조 (ERD 3.3·5.1·5.4·5.5절). */
  private static final Map<String, String> LEARNER_LINKED_COLUMNS =
      Map.of(
          "participants", "user_id",
          "learning_sessions", "user_id",
          "problem_attempts", "user_id",
          "user_skill_memory", "user_id");

  /** 강사가 가지는 users 참조 (ERD 3.2·4.3절). */
  private static final Map<String, String> INSTRUCTOR_LINKED_COLUMNS =
      Map.of(
          "lectures", "instructor_user_id",
          "problems", "creator_user_id");

  /** 문제 애그리거트의 하위 행. users를 직접 참조하지 않고 problems.id로 따라간다 (ERD 4.4·4.6절). */
  private static final List<String> PROBLEM_CHILD_TABLES =
      List.of("problem_cells", "problem_skills");

  @TestConfiguration(proxyBeanMethods = false)
  static class RepositoryUnderTest {
    @Bean
    UserRepository userRepository(UserJpaRepository jpaRepository) {
      return new UserRepositoryImpl(jpaRepository);
    }
  }

  @Autowired private UserRepository userRepository;
  @Autowired private MailOutboxJpaRepository mailOutboxJpaRepository;
  @Autowired private TestEntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;

  private final PasswordHasher hasher =
      new Argon2idPasswordHasher(new Argon2idProperties(19456, 2, 1));

  private JdbcTemplate jdbc;
  private RecordingRefreshTokenStore refreshTokens;
  private String currentHash;
  private String username;
  private String email;
  private long userId;

  @BeforeEach
  void setUp() {
    this.jdbc = new JdbcTemplate(this.dataSource);
    this.jdbc.execute("delete from mail_outbox");
    this.jdbc.execute("delete from users");
    this.refreshTokens = new RecordingRefreshTokenStore();
    this.currentHash = this.hasher.hash(PasswordPolicy.normalize(CURRENT_PASSWORD)).value();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    this.username = "learner" + suffix;
    this.email = "learner" + suffix + "@example.com";
    this.userId = insertUser(this.username, this.email, this.currentHash);
  }

  private long insertUser(String username, String email, String passwordHash) {
    return insertUser(username, email, passwordHash, UserRole.LEARNER);
  }

  /** INSTRUCTOR는 공개 가입으로 만들 수 없어 초기 데이터처럼 저장소에 직접 넣는다 (API 명세 3.1절). */
  private long insertUser(String username, String email, String passwordHash, UserRole role) {
    User user = User.create(username, passwordHash, role, Email.of(email), NOW);
    user.verifyEmail(NOW);
    return inTransaction(() -> this.userRepository.save(user)).getId();
  }

  private <T> T inTransaction(Supplier<T> action) {
    return new TransactionTemplate(this.transactionManager).execute(status -> action.get());
  }

  private UserWithdrawalService service() {
    return service(this.userRepository, UUID::randomUUID);
  }

  private UserWithdrawalService service(UserRepository users, Supplier<UUID> pseudonymSource) {
    return new UserWithdrawalService(
        users,
        new PasswordVerificationService(this.hasher),
        this.hasher,
        this.refreshTokens,
        new TransactionTemplate(this.transactionManager),
        pseudonymSource,
        Clock.fixed(WITHDRAWN_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
  }

  private void withdraw(UserWithdrawalService service, String currentPassword) {
    service.withdraw(new UserWithdrawalService.Command(this.userId, currentPassword));
  }

  private ErrorCode errorCodeOf(UserWithdrawalService service) {
    return catchThrowableOfType(BusinessException.class, () -> withdraw(service, CURRENT_PASSWORD))
        .getErrorCode();
  }

  /** 조건부 UPDATE는 트랜잭션을 요구한다. 운영 경로에서는 서비스가 열어 준다. */
  private int withdrawRow(
      long userId,
      long expectedRowVersion,
      String expectedPasswordHash,
      long expectedAuthVersion,
      String pseudonymUsername,
      String pseudonymEmail) {
    return inTransaction(
        () ->
            this.userRepository.withdraw(
                userId,
                expectedRowVersion,
                expectedPasswordHash,
                expectedAuthVersion,
                pseudonymUsername,
                Email.of(pseudonymEmail),
                OTHER_HASH,
                WITHDRAWN_AT));
  }

  private int withdrawRow(long expectedRowVersion, String expectedHash, long expectedAuthVersion) {
    return withdrawRow(
        this.userId,
        expectedRowVersion,
        expectedHash,
        expectedAuthVersion,
        "withdrawn-" + hex(FIRST),
        "withdrawn-" + hex(FIRST) + "@withdrawn.invalid");
  }

  private Map<String, Object> row(long id) {
    return this.jdbc.queryForMap("select * from users where id = ?", id);
  }

  private Map<String, Object> row() {
    return row(this.userId);
  }

  private User reload(long id) {
    return this.userRepository.findById(id).orElseThrow();
  }

  private static String hex(UUID uuid) {
    return uuid.toString().replace("-", "");
  }

  /** 호출 순서대로 UUID를 돌려준다. 다 쓰면 마지막 값을 계속 돌려준다. */
  private static Supplier<UUID> sequence(UUID... values) {
    Deque<UUID> queue = new ArrayDeque<>(List.of(values));
    return () -> queue.size() > 1 ? queue.poll() : queue.peek();
  }

  /** 조건부 UPDATE를 부르기 직전에 다른 처리를 끼워 넣는다. 나머지 메서드는 그대로 위임한다. */
  private UserRepository racingOnce(Runnable race, AtomicInteger withdrawCalls) {
    boolean[] done = {false};
    return (UserRepository)
        Proxy.newProxyInstance(
            UserRepository.class.getClassLoader(),
            new Class<?>[] {UserRepository.class},
            (proxy, method, args) -> {
              if (method.getName().equals("withdraw")) {
                withdrawCalls.incrementAndGet();
                if (!done[0]) {
                  done[0] = true;
                  race.run();
                }
              }
              return invoke(method, args);
            });
  }

  private Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(this.userRepository, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  // ---------- 확정 ----------

  @Test
  void 실제_DB가_MariaDB_11_8_9다() {
    assertThat(this.jdbc.queryForObject("select version()", String.class))
        .startsWith("11.8.9-MariaDB");
  }

  @Test
  void 한_문장으로_탈퇴_상태_가명_폐기해시_auth_version_row_version을_확정하고_나머지는_유지한다() {
    this.jdbc.update(
        "update users set failed_login_count = 2, locked_until = ?, auth_version = 4 where id = ?",
        NOW.minusMinutes(1),
        this.userId);
    Map<String, Object> before = row();

    assertThatCode(() -> withdraw(service(), CURRENT_PASSWORD)).doesNotThrowAnyException();

    Map<String, Object> after = row();
    User withdrawn = reload(this.userId);
    assertThat(after).containsEntry("status", "WITHDRAWN");
    assertThat(withdrawn.getWithdrawnAt()).isEqualTo(WITHDRAWN_AT);
    assertThat(withdrawn.getUpdatedAt()).isEqualTo(WITHDRAWN_AT);
    assertThat(after).containsEntry("email_verified_at", null);
    assertThat((String) after.get("username")).matches(PSEUDONYM_USERNAME);
    assertThat((String) after.get("email")).matches(PSEUDONYM_EMAIL);
    assertThat(after).containsEntry("auth_version", 5L);
    assertThat(after).containsEntry("row_version", (Long) before.get("row_version") + 1);
    String discardedHash = (String) after.get("password_hash");
    assertThat(discardedHash).startsWith("$argon2id$").isNotEqualTo(this.currentHash);
    assertThat(this.hasher.matches(PasswordPolicy.normalize(CURRENT_PASSWORD), discardedHash))
        .isFalse();
    for (String column :
        List.of("id", "role", "level", "created_at", "failed_login_count", "locked_until")) {
      assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
    }
    assertThat(this.refreshTokens.revokedUserIds).containsExactly(this.userId);
  }

  @Test
  void 폐기_해시와_가명은_탈퇴마다_다르다() {
    long firstId = this.userId;
    withdraw(service(), CURRENT_PASSWORD);
    this.userId =
        insertUser("second" + firstId, "second" + firstId + "@example.com", this.currentHash);
    withdraw(service(), CURRENT_PASSWORD);

    Map<String, Object> first = row(firstId);
    Map<String, Object> second = row(this.userId);
    for (String column : List.of("username", "email", "password_hash")) {
      assertThat(first.get(column)).as(column).isNotEqualTo(second.get(column));
    }
  }

  @Test
  void 원래_username과_email은_즉시_재사용되고_새_id를_받는다() {
    withdraw(service(), CURRENT_PASSWORD);

    long reRegisteredId = insertUser(this.username, this.email, this.currentHash);

    assertThat(reRegisteredId).isNotEqualTo(this.userId);
    assertThat(row(this.userId)).containsEntry("status", "WITHDRAWN");
    assertThat(row(reRegisteredId))
        .containsEntry("status", "ACTIVE")
        .containsEntry("username", this.username)
        .containsEntry("email", this.email);
  }

  @Test
  void 엔티티_탈퇴와_조건부_UPDATE가_같은_상태를_만든다() {
    long entityPathId =
        insertUser(
            "entity" + this.userId, "entity" + this.userId + "@example.com", this.currentHash);

    assertThat(withdrawRow(0L, this.currentHash, 0L)).isEqualTo(1);
    inTransaction(
        () -> {
          User user = reload(entityPathId);
          user.withdraw(
              "withdrawn-" + hex(SECOND),
              Email.of("withdrawn-" + hex(SECOND) + "@withdrawn.invalid"),
              OTHER_HASH,
              WITHDRAWN_AT);
          return this.userRepository.save(user);
        });

    Map<String, Object> bulk = row(this.userId);
    Map<String, Object> entity = row(entityPathId);
    for (String column :
        List.of(
            "status",
            "email_verified_at",
            "password_hash",
            "auth_version",
            "row_version",
            "role",
            "failed_login_count",
            "locked_until")) {
      assertThat(bulk.get(column)).as(column).isEqualTo(entity.get(column));
    }
    assertThat(reload(this.userId).getWithdrawnAt())
        .isEqualTo(reload(entityPathId).getWithdrawnAt());
    assertThat(reload(this.userId).getUpdatedAt()).isEqualTo(reload(entityPathId).getUpdatedAt());
  }

  // ---------- 조건부 갱신의 WHERE ----------

  @Test
  void 조건이_모두_맞으면_1행이고_auth_version을_저장값에서_1_올린다() {
    this.jdbc.update("update users set auth_version = 5 where id = ?", this.userId);

    assertThat(withdrawRow(0L, this.currentHash, 5L)).isEqualTo(1);
    assertThat(row()).containsEntry("auth_version", 6L).containsEntry("row_version", 1L);
  }

  @Test
  void row_version이_다르면_0행이다() {
    Map<String, Object> before = row();

    assertThat(withdrawRow(99L, this.currentHash, 0L)).isZero();
    assertThat(row()).isEqualTo(before);
  }

  @Test
  void password_hash가_다르면_0행이다() {
    Map<String, Object> before = row();

    assertThat(withdrawRow(0L, OTHER_HASH, 0L)).isZero();
    assertThat(row()).isEqualTo(before);
  }

  @Test
  void auth_version이_다르면_0행이다() {
    Map<String, Object> before = row();

    assertThat(withdrawRow(0L, this.currentHash, 1L)).isZero();
    assertThat(row()).isEqualTo(before);
  }

  @Test
  void ACTIVE가_아니면_0행이다() {
    this.jdbc.update(
        "update users set status = 'WITHDRAWN', withdrawn_at = ? where id = ?", NOW, this.userId);
    Map<String, Object> before = row();

    assertThat(withdrawRow(0L, this.currentHash, 0L)).isZero();
    assertThat(row()).isEqualTo(before);
  }

  @Test
  void 존재하지_않는_사용자는_0행이다() {
    assertThat(
            withdrawRow(
                this.userId + 987_654L,
                0L,
                this.currentHash,
                0L,
                "withdrawn-" + hex(FIRST),
                "withdrawn-" + hex(FIRST) + "@withdrawn.invalid"))
        .isZero();
  }

  // ---------- 현재 비밀번호 ----------

  @Test
  void 현재_비밀번호가_다르면_403이고_행이_그대로다() {
    Map<String, Object> before = row();

    ErrorCode code =
        catchThrowableOfType(
                BusinessException.class, () -> withdraw(service(), CURRENT_PASSWORD + " "))
            .getErrorCode();

    assertThat(code).isEqualTo(ErrorCode.CURRENT_PASSWORD_MISMATCH);
    // 실패 횟수도 늘리지 않는다. 행 전체가 그대로다.
    assertThat(row()).isEqualTo(before);
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  // ---------- 가명 UNIQUE 충돌 ----------

  @Test
  void 가명_username_충돌은_rollback_뒤_새_가명으로_재시도해_성공한다() {
    long blockerId =
        insertUser("withdrawn-" + hex(FIRST), "blocker" + this.userId + "@example.com", OTHER_HASH);
    Map<String, Object> blockerBefore = row(blockerId);

    assertThatCode(
            () -> withdraw(service(this.userRepository, sequence(FIRST, SECOND)), CURRENT_PASSWORD))
        .doesNotThrowAnyException();

    assertThat(row())
        .containsEntry("status", "WITHDRAWN")
        .containsEntry("username", "withdrawn-" + hex(SECOND))
        .containsEntry("email", "withdrawn-" + hex(SECOND) + "@withdrawn.invalid")
        .containsEntry("auth_version", 1L)
        .containsEntry("row_version", 1L);
    assertThat(row(blockerId)).isEqualTo(blockerBefore);
    assertThat(this.refreshTokens.revokedUserIds).containsExactly(this.userId);
  }

  @Test
  void 가명_email_충돌도_rollback_뒤_새_가명으로_재시도해_성공한다() {
    long blockerId =
        insertUser(
            "blocker" + this.userId, "withdrawn-" + hex(FIRST) + "@withdrawn.invalid", OTHER_HASH);
    Map<String, Object> blockerBefore = row(blockerId);

    withdraw(service(this.userRepository, sequence(FIRST, SECOND)), CURRENT_PASSWORD);

    assertThat(row())
        .containsEntry("status", "WITHDRAWN")
        .containsEntry("email", "withdrawn-" + hex(SECOND) + "@withdrawn.invalid");
    assertThat(row(blockerId)).isEqualTo(blockerBefore);
  }

  @Test
  void 충돌이_상한까지_이어지면_503이고_행이_그대로다() {
    insertUser("withdrawn-" + hex(FIRST), "blocker" + this.userId + "@example.com", OTHER_HASH);
    Map<String, Object> before = row();

    assertThat(errorCodeOf(service(this.userRepository, sequence(FIRST))))
        .isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(row()).isEqualTo(before);
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  // ---------- 경합 ----------

  @Test
  void 확인_뒤_비밀번호가_바뀌면_덮어쓰지_않고_503이다() {
    AtomicInteger calls = new AtomicInteger();
    UserRepository racing =
        racingOnce(
            () ->
                this.jdbc.update(
                    "update users set password_hash = ?, row_version = row_version + 1 where id = ?",
                    OTHER_HASH,
                    this.userId),
            calls);

    assertThat(errorCodeOf(service(racing, UUID::randomUUID)))
        .isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(row()).containsEntry("status", "ACTIVE").containsEntry("password_hash", OTHER_HASH);
    assertThat(calls).hasValue(1);
  }

  @Test
  void 확인_뒤_auth_version이_오르면_덮어쓰지_않고_503이다() {
    AtomicInteger calls = new AtomicInteger();
    UserRepository racing =
        racingOnce(
            () ->
                this.jdbc.update(
                    "update users set auth_version = auth_version + 1, row_version = row_version"
                        + " + 1 where id = ?",
                    this.userId),
            calls);

    assertThat(errorCodeOf(service(racing, UUID::randomUUID)))
        .isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(row()).containsEntry("status", "ACTIVE").containsEntry("auth_version", 1L);
  }

  @Test
  void row_version만_바뀐_경합도_재시도하지_않고_503이다() {
    AtomicInteger calls = new AtomicInteger();
    UserRepository racing =
        racingOnce(
            () ->
                this.jdbc.update(
                    "update users set row_version = row_version + 1 where id = ?", this.userId),
            calls);

    assertThat(errorCodeOf(service(racing, UUID::randomUUID)))
        .isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(row())
        .containsEntry("status", "ACTIVE")
        .containsEntry("username", this.username)
        .containsEntry("auth_version", 0L);
    assertThat(calls).hasValue(1);
  }

  @Test
  void 확인_뒤_다른_요청이_먼저_탈퇴시켰으면_401이다() {
    AtomicInteger calls = new AtomicInteger();
    UserRepository racing =
        racingOnce(
            () ->
                this.jdbc.update(
                    "update users set status = 'WITHDRAWN', withdrawn_at = ?, row_version ="
                        + " row_version + 1 where id = ?",
                    NOW,
                    this.userId),
            calls);

    assertThat(errorCodeOf(service(racing, UUID::randomUUID))).isEqualTo(ErrorCode.UNAUTHORIZED);

    assertThat(row()).containsEntry("username", this.username);
  }

  @Test
  void 갱신_뒤_같은_트랜잭션이_실패하면_rollback되어_503이고_행이_그대로다() {
    Map<String, Object> before = row();
    UserRepository failing =
        (UserRepository)
            Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[] {UserRepository.class},
                (proxy, method, args) -> {
                  Object result = invoke(method, args);
                  if (method.getName().equals("withdraw")) {
                    // 갱신 뒤 같은 트랜잭션에서 실패시킨다. rollback이 확정되면 이번 요청의 변경은 없다.
                    throw new QueryTimeoutException("응답 없음");
                  }
                  return result;
                });

    assertThat(errorCodeOf(service(failing, UUID::randomUUID)))
        .isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(row()).isEqualTo(before);
    assertThat(this.refreshTokens.revokedUserIds).isEmpty();
  }

  // ---------- 연결 데이터 보존 ----------

  @Test
  void ERD의_users_참조_컬럼_여섯_개를_학습자와_강사_검증으로_모두_덮는다() {
    Map<String, String> covered = new java.util.TreeMap<>(LEARNER_LINKED_COLUMNS);
    covered.putAll(INSTRUCTOR_LINKED_COLUMNS);

    assertThat(covered)
        .containsOnly(
            Map.entry("lectures", "instructor_user_id"),
            Map.entry("participants", "user_id"),
            Map.entry("problems", "creator_user_id"),
            Map.entry("problem_attempts", "user_id"),
            Map.entry("user_skill_memory", "user_id"),
            Map.entry("learning_sessions", "user_id"));
    // 실제 스키마에도 그 컬럼이 있어야 검증이 빈 조회로 통과하지 않는다.
    covered.forEach(
        (table, column) ->
            assertThat(
                    this.jdbc.queryForObject(
                        "select count(*) from information_schema.columns"
                            + " where table_schema = database() and table_name = ? and column_name = ?",
                        Long.class,
                        table,
                        column))
                .as(table + "." + column)
                .isEqualTo(1L));
  }

  @Test
  void 학습자가_탈퇴해도_사용자_행과_참여_학습_기록과_참조_값이_그대로_남는다() {
    long instructorId = insertInstructor("teacher" + this.userId, OTHER_HASH);
    seedLearnerRows(instructorId);
    MailOutboxEnqueueService outbox =
        new MailOutboxEnqueueService(new MailOutboxRepositoryImpl(this.mailOutboxJpaRepository));
    inTransaction(() -> outbox.enqueue(AccountActionPurpose.EMAIL_VERIFICATION, this.email, NOW));

    Map<String, List<Map<String, Object>>> linkedBefore = linkedRows(LEARNER_LINKED_COLUMNS);
    Map<String, Long> totalsBefore = totals();
    List<Map<String, Object>> outboxBefore = this.jdbc.queryForList("select * from mail_outbox");

    withdraw(service(), CURRENT_PASSWORD);

    assertRetained(LEARNER_LINKED_COLUMNS, linkedBefore, totalsBefore);
    assertThat(this.jdbc.queryForList("select * from mail_outbox")).isEqualTo(outboxBefore);
  }

  @Test
  void 강사가_탈퇴해도_담당_수업과_등록한_문제와_문제_하위_행이_그대로_남는다() {
    this.userId = insertInstructor("teacher" + this.userId, this.currentHash);
    long problemId = seedInstructorRows();

    Map<String, List<Map<String, Object>>> linkedBefore = linkedRows(INSTRUCTOR_LINKED_COLUMNS);
    Map<String, List<Map<String, Object>>> childrenBefore = problemChildRows(problemId);
    Map<String, Long> totalsBefore = totals();

    withdraw(service(), CURRENT_PASSWORD);

    assertRetained(INSTRUCTOR_LINKED_COLUMNS, linkedBefore, totalsBefore);
    assertThat(this.jdbc.queryForMap("select * from problems where id = ?", problemId))
        .containsEntry("creator_user_id", this.userId)
        .containsEntry("source_type", "INSTRUCTOR");
    childrenBefore.forEach((table, rows) -> assertThat(rows).as(table).isNotEmpty());
    assertThat(problemChildRows(problemId)).isEqualTo(childrenBefore);
  }

  @Test
  void 탈퇴한_학습자와_같은_식별자로_다시_가입한_사용자는_과거_기록과_연결되지_않는다() {
    seedLearnerRows(insertInstructor("teacher" + this.userId, OTHER_HASH));
    withdraw(service(), CURRENT_PASSWORD);

    long reRegisteredId = insertUser(this.username, this.email, this.currentHash);

    assertNotLinked(LEARNER_LINKED_COLUMNS, reRegisteredId);
  }

  @Test
  void 탈퇴한_강사와_같은_식별자로_다시_가입한_사용자는_과거_수업과_문제에_연결되지_않는다() {
    String instructorUsername = "teacher" + this.userId;
    this.userId = insertInstructor(instructorUsername, this.currentHash);
    seedInstructorRows();
    withdraw(service(), CURRENT_PASSWORD);

    // 공개 재가입은 LEARNER로 만들어진다 (API 명세 3.1절).
    long reRegisteredId =
        insertUser(instructorUsername, instructorUsername + "@example.com", this.currentHash);

    assertThat(reRegisteredId).isNotEqualTo(this.userId);
    assertNotLinked(INSTRUCTOR_LINKED_COLUMNS, reRegisteredId);
  }

  private long insertInstructor(String username, String passwordHash) {
    return insertUser(username, username + "@example.com", passwordHash, UserRole.INSTRUCTOR);
  }

  /** 탈퇴 대상 행이 남고, 참조 행이 비어 있지 않으며 내용·참조 값·전체 행 수가 그대로인지 확인한다. */
  private void assertRetained(
      Map<String, String> columns,
      Map<String, List<Map<String, Object>>> linkedBefore,
      Map<String, Long> totalsBefore) {
    assertThat(
            this.jdbc.queryForObject(
                "select count(*) from users where id = ?", Long.class, this.userId))
        .isEqualTo(1L);
    assertThat(row()).containsEntry("status", "WITHDRAWN");
    linkedBefore.forEach(
        (table, rows) -> {
          assertThat(rows).as(table).isNotEmpty();
          rows.forEach(
              linked ->
                  assertThat(linked.get(columns.get(table))).as(table).isEqualTo(this.userId));
        });
    assertThat(linkedRows(columns)).isEqualTo(linkedBefore);
    assertThat(totals()).isEqualTo(totalsBefore);
  }

  /** 새 id에는 과거 참조가 하나도 없고, 과거 참조는 탈퇴한 id에 그대로 남는다. */
  private void assertNotLinked(Map<String, String> columns, long reRegisteredId) {
    assertThat(reRegisteredId).isNotEqualTo(this.userId);
    columns.forEach(
        (table, column) -> {
          assertThat(countBy(table, column, reRegisteredId)).as(table).isZero();
          assertThat(countBy(table, column, this.userId)).as(table).isPositive();
        });
  }

  /**
   * 탈퇴 대상 강사가 담당하는 수업과 등록한 INSTRUCTOR 문제를 만든다. 문제·Cell·요소 연결은 각각 별도 엔티티이므로 같은 트랜잭션에서 차례로 저장한다 (ERD
   * 4.3절).
   *
   * @return 등록한 문제의 id
   */
  /** learning_skills는 (skill_type, metadata)가 유니크다. 시드를 여러 번 돌려도 충돌하지 않게 값을 분리한다. */
  private static String uniqueMetadata() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private long seedInstructorRows() {
    return inTransaction(
        () -> {
          this.entityManager.persist(
              Lecture.schedule(
                  this.userId, "점자 기초", "탈퇴 보존 확인", participationCode(), NOW.plusDays(1), NOW));
          Problem problem = this.entityManager.persistFlushFind(new Problem(Level.WORD, "보존"));
          List<Integer> patterns = List.of(0b000001, 0b000011);
          for (int index = 0; index < patterns.size(); index++) {
            this.entityManager.persist(new ProblemCell(problem, index + 1, patterns.get(index)));
          }
          LearningSkill skill =
              this.entityManager.persistFlushFind(
                  LearningSkill.of(SkillType.SKILL_01, uniqueMetadata(), "초성 ㄱ", null));
          this.entityManager.persist(new ProblemSkill(problem, skill));
          return problem.getId();
        });
  }

  /** 탈퇴 대상 학습자의 참여·세션·풀이·숙련 상태 행을 만든다. 참여한 수업의 담당자는 별도 강사다. */
  private void seedLearnerRows(long instructorId) {
    inTransaction(
        () -> {
          Long attendedLectureId =
              this.entityManager.persistAndGetId(
                  Lecture.schedule(
                      instructorId,
                      "점자 심화",
                      "참여 기록 보존 확인",
                      participationCode(),
                      NOW.plusDays(2),
                      NOW),
                  Long.class);
          this.entityManager.persist(Participant.join(attendedLectureId, this.userId, NOW));
          User learner = this.entityManager.find(User.class, this.userId);
          Long sessionId =
              this.entityManager.persistAndGetId(
                  new LearningSession(learner, null), Long.class);
          this.entityManager.persist(
              ProblemAttempt.forFreeSession(this.userId, 1L, sessionId, 1, 1L, true, NOW));
          return null;
        });
  }

  private static String participationCode() {
    return "%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000));
  }

  private Map<String, List<Map<String, Object>>> linkedRows(Map<String, String> columns) {
    Map<String, List<Map<String, Object>>> rows = new java.util.TreeMap<>();
    columns.forEach(
        (table, column) ->
            rows.put(
                table,
                this.jdbc.queryForList(
                    "select * from " + table + " where " + column + " = ? order by 1",
                    this.userId)));
    return rows;
  }

  private Map<String, List<Map<String, Object>>> problemChildRows(long problemId) {
    Map<String, List<Map<String, Object>>> rows = new java.util.TreeMap<>();
    for (String table : PROBLEM_CHILD_TABLES) {
      rows.put(
          table,
          this.jdbc.queryForList(
              "select * from " + table + " where problem_id = ? order by 1, 2", problemId));
    }
    return rows;
  }

  private Map<String, Long> totals() {
    Map<String, Long> totals = new java.util.TreeMap<>();
    List<String> tables = new ArrayList<>(LEARNER_LINKED_COLUMNS.keySet());
    tables.addAll(INSTRUCTOR_LINKED_COLUMNS.keySet());
    tables.addAll(PROBLEM_CHILD_TABLES);
    for (String table : tables) {
      totals.put(table, this.jdbc.queryForObject("select count(*) from " + table, Long.class));
    }
    totals.put("users", this.jdbc.queryForObject("select count(*) from users", Long.class));
    return totals;
  }

  private long countBy(String table, String column, long id) {
    return this.jdbc.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?", Long.class, id);
  }

  // ---------- 도우미 ----------

  private static final class RecordingRefreshTokenStore implements RefreshTokenStore {

    private final List<Long> revokedUserIds = new ArrayList<>();

    @Override
    public RefreshTokenStore.AllFamiliesRevocationResult revokeAllFamilies(long userId) {
      this.revokedUserIds.add(userId);
      return RefreshTokenStore.AllFamiliesRevocationResult.REVOKED;
    }

    @Override
    public RefreshTokenStore.LookupResult lookup(RefreshTokenStore.TokenRecord submitted) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RefreshTokenStore.FamilyIssueResult issueFamily(
        RefreshTokenStore.TokenRecord firstToken) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RefreshTokenStore.RotationResult rotate(
        RefreshTokenStore.TokenRecord current, RefreshTokenStore.TokenRecord next) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RefreshTokenStore.FamilyRevocationResult revokeFamily(long userId, String familyId) {
      throw new UnsupportedOperationException();
    }
  }
}
