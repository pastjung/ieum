package com.itda.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로그인 실패 누적과 성공 초기화를 운영과 같은 MariaDB 11.8.9에 대고 검증한다 (ERD 3.1절, API 명세 3.6.2절).
 *
 * <p>H2로는 증명되지 않는 것만 여기서 본다 — 한 문장에서 카운터 증가와 잠금 기록이 함께 일어나는지, 동시 실패가 서로의 증가를 덮어쓰지 않는지, 잠긴 행이 다시 밀리지
 * 않는지, 성공 초기화의 세 조건이 실제 WHERE로 동작하는지, {@code row_version}이 오르고 {@code auth_version}이 그대로인지.
 *
 * <p><b>테스트 트랜잭션을 쓰지 않는다.</b> 동시 갱신을 보려면 각 스레드가 자기 트랜잭션을 써야 한다. 대신 각 테스트가 시작할 때 자기 데이터를 직접 지운다.
 */
@Tag("mariadb")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(LoginMariadbTest.RepositoryUnderTest.class)
class LoginMariadbTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 9, 0);
  private static final LocalDateTime LOCKED_UNTIL = NOW.plusMinutes(10);

  @TestConfiguration(proxyBeanMethods = false)
  static class RepositoryUnderTest {
    @Bean
    UserRepository userRepository(UserJpaRepository jpaRepository) {
      return new UserRepositoryImpl(jpaRepository);
    }
  }

  @Autowired private UserRepository userRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;

  private JdbcTemplate jdbc;
  private long userId;
  private String username;

  @BeforeEach
  void setUp() {
    this.jdbc = new JdbcTemplate(this.dataSource);
    this.jdbc.execute("delete from mail_outbox");
    this.jdbc.execute("delete from users");
    this.username = "learner" + UUID.randomUUID().toString().substring(0, 8);
    this.userId = insertUser();
  }

  private long insertUser() {
    User user =
        User.create(
            this.username,
            "argon2id$hash",
            UserRole.LEARNER,
            Email.of(this.username + "@example.com"),
            NOW);
    user.verifyEmail(NOW);
    return transaction().execute(status -> this.userRepository.save(user)).getId();
  }

  private TransactionTemplate transaction() {
    return new TransactionTemplate(this.transactionManager);
  }

  private int recordFailure() {
    return transaction()
        .execute(
            status ->
                this.userRepository.recordLoginFailure(
                    this.userId, User.MAX_FAILED_LOGIN_ATTEMPTS, LOCKED_UNTIL, NOW));
  }

  private int recordSuccess(long expectedRowVersion, long expectedAuthVersion) {
    return transaction()
        .execute(
            status ->
                this.userRepository.recordLoginSuccess(
                    this.userId, expectedRowVersion, expectedAuthVersion, NOW));
  }

  private Map<String, Object> row() {
    return this.jdbc.queryForMap("select * from users where id = ?", this.userId);
  }

  // ---------- 환경 ----------

  @Test
  void 실제_DB가_MariaDB_11_8_9다() {
    assertThat(this.jdbc.queryForObject("select version()", String.class))
        .startsWith("11.8.9-MariaDB");
  }

  @Test
  void 대소문자가_달라도_같은_계정으로_조회된다() {
    // 로그인은 대소문자 비구분 조회를 쓴다. 이 성질은 MariaDB collation에서만 성립한다 (ERD 3.1절).
    assertThat(this.userRepository.findByUsername(this.username.toUpperCase())).isPresent();
  }

  // ---------- 실패 누적 ----------

  @Test
  void 실패는_카운터를_올리고_row_version도_함께_올린다() {
    assertThat(recordFailure()).isEqualTo(1);

    Map<String, Object> after = row();
    assertThat(after).containsEntry("failed_login_count", 1);
    assertThat(after).containsEntry("locked_until", null);
    assertThat(after).containsEntry("row_version", 1L);
    assertThat(after).containsEntry("auth_version", 0L);
  }

  @Test
  void 네_번째까지는_잠그지_않고_다섯_번째에_잠근다() {
    for (int attempt = 1; attempt <= User.MAX_FAILED_LOGIN_ATTEMPTS - 1; attempt++) {
      assertThat(recordFailure()).isEqualTo(1);
      assertThat(row()).containsEntry("failed_login_count", attempt);
      assertThat(row()).containsEntry("locked_until", null);
    }

    assertThat(recordFailure()).isEqualTo(1);

    assertThat(row()).containsEntry("failed_login_count", User.MAX_FAILED_LOGIN_ATTEMPTS);
    assertThat(row().get("locked_until")).isNotNull();
  }

  @Test
  void 잠긴_행은_갱신되지_않아_제한_시각이_밀리지_않는다() {
    for (int attempt = 0; attempt < User.MAX_FAILED_LOGIN_ATTEMPTS; attempt++) {
      recordFailure();
    }
    Object lockedUntil = row().get("locked_until");
    long rowVersion = (long) row().get("row_version");

    // 잠금 중 재시도로 제한 종료 시각이 계속 밀리면 사실상 영구 잠금이 된다.
    assertThat(recordFailure()).isZero();
    assertThat(row().get("locked_until")).isEqualTo(lockedUntil);
    assertThat(row()).containsEntry("row_version", rowVersion);
    assertThat(row()).containsEntry("failed_login_count", User.MAX_FAILED_LOGIN_ATTEMPTS);
  }

  @Test
  void 탈퇴한_계정에는_실패를_기록하지_않는다() {
    this.jdbc.update(
        "update users set status = 'WITHDRAWN', withdrawn_at = ? where id = ?", NOW, this.userId);

    assertThat(recordFailure()).isZero();
    assertThat(row()).containsEntry("failed_login_count", 0);
  }

  @Test
  void 동시_실패도_정확히_누적된다() throws Exception {
    int attempts = 4;
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Integer>> failures =
        IntStream.range(0, attempts)
            .<Callable<Integer>>mapToObj(
                i ->
                    () -> {
                      start.await();
                      return recordFailure();
                    })
            .toList();

    List<Integer> updated = runConcurrently(failures, start);

    // 읽고-더하고-저장했다면 서로의 증가를 덮어써 4보다 작아진다.
    assertThat(updated).containsOnly(1);
    assertThat(row()).containsEntry("failed_login_count", attempts);
    assertThat(row()).containsEntry("row_version", (long) attempts);
  }

  // ---------- 성공 초기화 ----------

  @Test
  void 성공은_카운터와_잠금을_비우고_row_version만_올린다() {
    recordFailure();
    recordFailure();
    long rowVersion = (long) row().get("row_version");

    assertThat(recordSuccess(rowVersion, 0L)).isEqualTo(1);

    Map<String, Object> after = row();
    assertThat(after).containsEntry("failed_login_count", 0);
    assertThat(after).containsEntry("locked_until", null);
    assertThat(after).containsEntry("row_version", rowVersion + 1);
    // 로그인은 기존 세션을 무효화하는 처리가 아니다.
    assertThat(after).containsEntry("auth_version", 0L);
  }

  @Test
  void 잠긴_계정도_조건이_맞으면_성공_초기화로_풀린다() {
    for (int attempt = 0; attempt < User.MAX_FAILED_LOGIN_ATTEMPTS; attempt++) {
      recordFailure();
    }
    long rowVersion = (long) row().get("row_version");

    // 잠금 해제는 제한 시각이 지난 뒤의 성공 로그인이 한다. UPDATE 자체는 잠금을 조건으로 삼지 않는다.
    assertThat(recordSuccess(rowVersion, 0L)).isEqualTo(1);
    assertThat(row()).containsEntry("locked_until", null);
  }

  @Test
  void row_version이_다르면_0행이다() {
    assertThat(recordSuccess(99L, 0L)).isZero();
    assertThat(row()).containsEntry("row_version", 0L);
  }

  @Test
  void auth_version이_다르면_0행이다() {
    // 비밀번호 재설정·탈퇴가 끼어든 경우다. 구 자격 증명으로 세션을 만들지 않는다.
    assertThat(recordSuccess(0L, 99L)).isZero();
  }

  @Test
  void ACTIVE가_아니면_0행이다() {
    this.jdbc.update(
        "update users set status = 'WITHDRAWN', withdrawn_at = ? where id = ?", NOW, this.userId);

    assertThat(recordSuccess(0L, 0L)).isZero();
  }

  @Test
  void 존재하지_않는_사용자는_0행이다() {
    long absentUserId = this.userId + 987_654L;

    int updated =
        transaction()
            .execute(status -> this.userRepository.recordLoginSuccess(absentUserId, 0L, 0L, NOW));

    assertThat(updated).isZero();
  }

  @Test
  void 동시_성공_초기화는_하나만_통과한다() throws Exception {
    int attempts = 6;
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Integer>> resets =
        IntStream.range(0, attempts)
            .<Callable<Integer>>mapToObj(
                i ->
                    () -> {
                      start.await();
                      return recordSuccess(0L, 0L);
                    })
            .toList();

    List<Integer> updated = runConcurrently(resets, start);

    // 같은 Snapshot을 조건으로 하므로 첫 갱신이 row_version을 올린 뒤에는 전부 0행이다.
    assertThat(updated.stream().filter(count -> count == 1).count()).isEqualTo(1);
    assertThat(row()).containsEntry("row_version", 1L);
  }

  // ---------- 도우미 ----------

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
