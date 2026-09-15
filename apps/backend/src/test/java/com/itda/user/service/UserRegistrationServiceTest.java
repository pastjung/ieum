package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.Email;
import com.itda.user.domain.MailDispatchStatus;
import com.itda.user.domain.MailOutboxClaim;
import com.itda.user.domain.MailOutboxEntry;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.domain.UserStatus;
import com.itda.user.repository.MailOutboxRepository;
import com.itda.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 회원가입의 판정과 오류 분류를 저장소 없이 고정한다 (API 명세 3.2.1·3.6.2절).
 *
 * <p>여기서 다루는 것은 "무엇을 어떤 code로 거부하고, 무엇을 언제 호출하는가"다. 실제 UNIQUE 경쟁과 두 INSERT의 원자성은 DB가 있어야 증명되므로
 * {@code MariaDB} 테스트가 맡는다.
 */
class UserRegistrationServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
  private static final String VALID_PASSWORD = "충분히 긴 새 비밀번호 문구";

  private RecordingUserRepository users;
  private RecordingMailOutboxRepository outbox;
  private RecordingCompromisedPasswordChecker breachChecker;
  private RecordingPasswordHasher hasher;
  private RecordingTransactionOperations transaction;
  private UserRegistrationService service;

  @BeforeEach
  void setUp() {
    this.users = new RecordingUserRepository();
    this.outbox = new RecordingMailOutboxRepository();
    this.breachChecker = new RecordingCompromisedPasswordChecker();
    this.hasher = new RecordingPasswordHasher();
    this.transaction = new RecordingTransactionOperations();
    this.transaction.hasher = this.hasher;
    this.service =
        new UserRegistrationService(
            this.users,
            new MailOutboxEnqueueService(this.outbox),
            new NewPasswordService(this.breachChecker, this.hasher),
            this.transaction,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static UserRegistrationService.Command command(
      String username, String email, String password) {
    return new UserRegistrationService.Command(username, email, password);
  }

  private static UserRegistrationService.Command validCommand() {
    return command("learner01", "learner@example.com", VALID_PASSWORD);
  }

  private UserSummary register(UserRegistrationService.Command command) {
    return this.service.register(command);
  }

  private ErrorCode errorCodeOf(UserRegistrationService.Command command) {
    return catchThrowableOfType(BusinessException.class, () -> register(command)).getErrorCode();
  }

  // ---------- 성공 ----------

  @Test
  void 가입하면_LEARNER_ACTIVE_미인증_계정을_만든다() {
    UserSummary summary = register(validCommand());

    User saved = this.users.saved.get(0);
    assertThat(saved.getRole()).isEqualTo(UserRole.LEARNER);
    assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(saved.getEmailVerifiedAt()).isNull();
    assertThat(saved.getLevel()).isNull();
    assertThat(saved.getWithdrawnAt()).isNull();
    assertThat(saved.getFailedLoginCount()).isZero();
    assertThat(saved.getLockedUntil()).isNull();
    assertThat(saved.getAuthVersion()).isZero();
    assertThat(summary.role()).isEqualTo(UserRole.LEARNER);
    assertThat(summary.emailVerified()).isFalse();
    assertThat(summary.level()).isNull();
  }

  @Test
  void 생성_시각은_주입한_UTC_시계를_쓴다() {
    register(validCommand());

    LocalDateTime expected = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    User saved = this.users.saved.get(0);
    assertThat(saved.getCreatedAt()).isEqualTo(expected);
    assertThat(saved.getUpdatedAt()).isEqualTo(expected);
  }

  @Test
  void 아이디는_trim_NFC_정규화해_저장한다() {
    register(command("  Learner.01  ", "learner@example.com", VALID_PASSWORD));

    assertThat(this.users.saved.get(0).getUsername()).isEqualTo("Learner.01");
  }

  @Test
  void 이메일은_trim_소문자_정규화해_저장한다() {
    register(command("learner01", "  Learner@Example.COM  ", VALID_PASSWORD));

    assertThat(this.users.saved.get(0).getEmail().getValue()).isEqualTo("learner@example.com");
  }

  @Test
  void 비밀번호는_해시로만_저장한다() {
    register(validCommand());

    String stored = this.users.saved.get(0).getPasswordHash();
    assertThat(stored).isNotEqualTo(VALID_PASSWORD).startsWith("$argon2id$");
    assertThat(this.hasher.hashCallCount()).isEqualTo(1);
  }

  @Test
  void 서버가_정하는_필드는_요청으로_바꿀_수_없다() {
    // 명령 타입에 자리가 없다는 것을 구조로 확인한다. 필드가 늘면 이 단언이 먼저 깨진다.
    assertThat(UserRegistrationService.Command.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly("username", "email", "password");
  }

  // ---------- 메일 작업 ----------

  @Test
  void 같은_트랜잭션에서_이메일_인증_메일_작업을_접수한다() {
    register(validCommand());

    assertThat(this.transaction.executions).isEqualTo(1);
    assertThat(this.outbox.saved).hasSize(1);
    MailOutboxEntry entry = this.outbox.saved.get(0);
    assertThat(entry.getPurpose()).isEqualTo(AccountActionPurpose.EMAIL_VERIFICATION);
    assertThat(entry.getRecipientEmail()).isEqualTo("learner@example.com");
    assertThat(entry.getStatus()).isEqualTo(MailDispatchStatus.PENDING);
    // 두 INSERT가 같은 트랜잭션 안에서 일어났다.
    assertThat(this.transaction.actionsInsideTransaction).isEqualTo(2);
  }

  @Test
  void 메일_작업에_정규화된_이메일을_넘긴다() {
    register(command("learner01", "  Learner@Example.COM ", VALID_PASSWORD));

    assertThat(this.outbox.saved.get(0).getRecipientEmail()).isEqualTo("learner@example.com");
  }

  // ---------- 입력 검증 ----------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ab",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", // 31자
        ".abc",
        "abc-",
        "learner 01",
        "러너학습자"
      })
  void 아이디_형식_위반은_INVALID_ACCOUNT_INPUT이다(String username) {
    assertThat(errorCodeOf(command(username, "learner@example.com", VALID_PASSWORD)))
        .isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"learner", "learner@", "@example.com", "learner example@example.com"})
  void 이메일_형식_위반은_INVALID_ACCOUNT_INPUT이다(String email) {
    assertThat(errorCodeOf(command("learner01", email, VALID_PASSWORD)))
        .isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);
  }

  @Test
  void 이메일이_254자를_넘으면_INVALID_ACCOUNT_INPUT이다() {
    String tooLong = "a".repeat(243) + "@example.com";
    assertThat(tooLong).hasSizeGreaterThan(Email.MAX_LENGTH);

    assertThat(errorCodeOf(command("learner01", tooLong, VALID_PASSWORD)))
        .isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);
  }

  @Test
  void 필수_필드가_없으면_INVALID_ACCOUNT_INPUT이다() {
    assertThat(errorCodeOf(command(null, "learner@example.com", VALID_PASSWORD)))
        .isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);
    assertThat(errorCodeOf(command("learner01", null, VALID_PASSWORD)))
        .isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);
    assertThat(errorCodeOf(command("learner01", "learner@example.com", null)))
        .isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);
  }

  @Test
  void 비밀번호_null은_정책_위반이_아니라_필수_입력_누락이다() {
    assertThat(errorCodeOf(command("learner01", "learner@example.com", null)))
        .isEqualTo(ErrorCode.INVALID_ACCOUNT_INPUT);
    // 정책 판정에 들어가지 않았다.
    assertThat(this.breachChecker.checkCallCount()).isZero();
    assertThat(this.hasher.hashCallCount()).isZero();
  }

  // ---------- 비밀번호 정책 ----------

  @Test
  void 짧은_비밀번호는_INVALID_PASSWORD다() {
    assertThat(errorCodeOf(command("learner01", "learner@example.com", "short")))
        .isEqualTo(ErrorCode.INVALID_PASSWORD);
  }

  @Test
  void 유출된_비밀번호는_INVALID_PASSWORD다() {
    this.breachChecker.willReturn(CompromisedPasswordChecker.CheckResult.COMPROMISED);

    assertThat(errorCodeOf(validCommand())).isEqualTo(ErrorCode.INVALID_PASSWORD);
  }

  @Test
  void 유출_조회_장애는_PASSWORD_POLICY_UNAVAILABLE이다() {
    this.breachChecker.willReturn(CompromisedPasswordChecker.CheckResult.UNAVAILABLE);

    // AUTH_STATE_UNAVAILABLE로 바꾸지 않는다 (3.2.1절).
    assertThat(errorCodeOf(validCommand())).isEqualTo(ErrorCode.PASSWORD_POLICY_UNAVAILABLE);
  }

  @Test
  void 정책_실패와_조회_장애에서는_저장소를_부르지_않는다() {
    this.breachChecker.willReturn(CompromisedPasswordChecker.CheckResult.COMPROMISED);
    assertThatThrownBy(() -> register(validCommand())).isInstanceOf(BusinessException.class);

    this.breachChecker.willReturn(CompromisedPasswordChecker.CheckResult.UNAVAILABLE);
    assertThatThrownBy(() -> register(validCommand())).isInstanceOf(BusinessException.class);

    assertThat(this.users.saved).isEmpty();
    assertThat(this.outbox.saved).isEmpty();
    assertThat(this.transaction.executions).isZero();
  }

  @Test
  void 입력_형식_위반에서도_유출_조회와_저장소를_부르지_않는다() {
    assertThatThrownBy(() -> register(command("ab", "learner@example.com", VALID_PASSWORD)))
        .isInstanceOf(BusinessException.class);

    assertThat(this.breachChecker.checkCallCount()).isZero();
    assertThat(this.transaction.executions).isZero();
  }

  @Test
  void 비밀번호_정책은_트랜잭션을_열기_전에_끝난다() {
    register(validCommand());

    assertThat(this.hasher.hashCallCount()).isEqualTo(1);
    assertThat(this.transaction.hashCallCountWhenTransactionOpened).isEqualTo(1);
  }

  // ---------- 저장소 오류 분류 ----------

  @Test
  void 아이디_중복이_확인되면_ACCOUNT_IDENTIFIER_UNAVAILABLE이다() {
    this.users.usernameTaken = true;
    this.transaction.willFail(uniqueViolation("UKr43af9ap4edm43mmtq01oddj6"));

    assertThat(errorCodeOf(validCommand())).isEqualTo(ErrorCode.ACCOUNT_IDENTIFIER_UNAVAILABLE);
  }

  @Test
  void 이메일_중복도_같은_code다() {
    this.users.emailTaken = true;
    this.transaction.willFail(uniqueViolation("UK6dotkott2kjsp8vw4d0m25fb7"));

    assertThat(errorCodeOf(validCommand())).isEqualTo(ErrorCode.ACCOUNT_IDENTIFIER_UNAVAILABLE);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "UKr43af9ap4edm43mmtq01oddj6", // 기존 DB에 남아 있는 Hibernate 생성 이름
        "uk_users_username", // 이름을 명시해 만든 DB
        "PUBLIC.UK_USERS_USERNAME", // 스키마가 붙는 방언
        "public.x INDEX public.x_INDEX_6", // 뒷받침 인덱스까지 붙는 방언
        "" // 이름을 읽지 못하는 경우
      })
  void 제약_이름이_무엇이든_판정이_같다(String constraintName) {
    // 이름은 스키마를 언제 누가 만들었는지에 따라 달라진다. 판정 근거로 쓰면 DB마다 결과가 갈린다.
    this.users.usernameTaken = true;
    this.transaction.willFail(uniqueViolation(constraintName));

    assertThat(errorCodeOf(validCommand())).isEqualTo(ErrorCode.ACCOUNT_IDENTIFIER_UNAVAILABLE);
  }

  @Test
  void 충돌이_확인되지_않으면_409가_아니라_503이다() {
    // 경쟁하던 쪽이 그사이 rollback했거나 아이디·이메일이 아닌 UNIQUE가 걸린 경우다. 추측해서 409를 주지 않는다.
    this.transaction.willFail(uniqueViolation("uk_users_username"));

    assertThat(errorCodeOf(validCommand())).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  void UNIQUE가_아닌_제약_위반은_409가_아니다() {
    // 이름이 아이디 UNIQUE처럼 보여도 종류가 다르면 중복이 아니다.
    this.users.usernameTaken = true;
    this.transaction.willFail(constraintViolation(ConstraintKind.CHECK, "uk_users_username"));

    assertThat(errorCodeOf(validCommand())).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  void 제약_종류를_읽지_못하면_중복으로_추측하지_않는다() {
    this.users.usernameTaken = true;
    this.transaction.willFail(new DataIntegrityViolationException("무결성 위반"));

    assertThat(errorCodeOf(validCommand())).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  void 중복_확인_조회가_실패하면_409로_단정하지_않는다() {
    this.users.usernameTaken = true;
    this.users.existsFailure = new QueryTimeoutException("응답 없음");
    this.transaction.willFail(uniqueViolation("uk_users_username"));

    assertThat(errorCodeOf(validCommand())).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  void 중복_확인은_실패한_뒤에만_한다() {
    register(validCommand());

    // 성공 경로에 사전 조회를 끼우면 조회와 INSERT 사이의 틈이 판정 근거가 되어 버린다.
    assertThat(this.users.existsCallCount).isZero();
  }

  @Test
  void 저장소_명령_실패는_AUTH_STATE_UNAVAILABLE이다() {
    this.transaction.willFail(new QueryTimeoutException("응답 없음"));

    assertThat(errorCodeOf(validCommand())).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  void commit_실패도_AUTH_STATE_UNAVAILABLE이다() {
    // execute가 반환되기 직전(= commit 자리)에서 터진다. 트랜잭션 안에서 잡았다면 여기서 성공 응답이 나가 버린다.
    this.transaction.willFailAfterAction(new TransactionSystemException("commit 실패"));

    assertThat(errorCodeOf(validCommand())).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.transaction.actionsInsideTransaction).isEqualTo(2);
  }

  @Test
  void 결과가_불확실한_실패는_사후_조회로_409가_되지_않는다() {
    // 아이디가 실제로 쓰이고 있어도 마찬가지다. commit 결과를 모르는 것과 중복인 것은 다른 사실이다 (3.6.2절).
    this.users.usernameTaken = true;
    this.transaction.willFailAfterAction(new TransactionSystemException("commit 결과 불확실"));

    assertThat(errorCodeOf(validCommand())).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.users.existsCallCount).isZero();
  }

  @Test
  void commit_단계에서_드러난_UNIQUE_충돌도_409다() {
    this.users.usernameTaken = true;
    this.transaction.willFailAfterAction(uniqueViolation("uk_users_username"));

    assertThat(errorCodeOf(validCommand())).isEqualTo(ErrorCode.ACCOUNT_IDENTIFIER_UNAVAILABLE);
  }

  // ---------- 노출 ----------

  @Test
  void 오류_message에_입력값도_비밀번호도_없다() {
    this.users.usernameTaken = true;
    this.transaction.willFail(uniqueViolation("uk_users_username"));

    BusinessException thrown =
        catchThrowableOfType(BusinessException.class, () -> register(validCommand()));

    assertThat(thrown.getErrorCode().getMessage())
        .doesNotContain("learner01")
        .doesNotContain("learner@example.com")
        .doesNotContain(VALID_PASSWORD)
        .doesNotContain("username")
        .doesNotContain("email");
    // 원인 예외를 붙이지 않아 SQL·제약 이름·중복 값이 예외 사슬로 흘러가지 않는다.
    assertThat(thrown.getCause()).isNull();
  }

  @Test
  void 명령과_요약의_toString에_값이_없다() {
    UserSummary summary = register(validCommand());

    assertThat(validCommand()).hasToString("UserRegistrationCommand[REDACTED]");
    assertThat(summary).hasToString("UserSummary[REDACTED]");
  }

  // ---------- 도우미 ----------

  private static DataIntegrityViolationException uniqueViolation(String constraintName) {
    return constraintViolation(ConstraintKind.UNIQUE, constraintName);
  }

  /** 방언이 채우는 모양 그대로 만든다 — 종류와 이름이 함께 온다. */
  private static DataIntegrityViolationException constraintViolation(
      ConstraintKind kind, String constraintName) {
    return new DataIntegrityViolationException(
        "무결성 위반",
        new ConstraintViolationException(
            "제약 위반",
            new java.sql.SQLIntegrityConstraintViolationException("제약 위반", "23000", 1062),
            kind,
            constraintName));
  }

  /** 트랜잭션 경계를 흉내 낸다. {@code execute}가 반환되기 직전이 commit 자리다. */
  private static final class RecordingTransactionOperations implements TransactionOperations {

    private int executions;
    private int actionsInsideTransaction;
    private int hashCallCountWhenTransactionOpened = -1;
    private RuntimeException failureBeforeAction;
    private RuntimeException failureAfterAction;

    private RecordingPasswordHasher hasher;

    void willFail(RuntimeException failure) {
      this.failureBeforeAction = failure;
    }

    void willFailAfterAction(RuntimeException failure) {
      this.failureAfterAction = failure;
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) {
      this.executions++;
      if (this.hasher != null) {
        this.hashCallCountWhenTransactionOpened = this.hasher.hashCallCount();
      }
      if (this.failureBeforeAction != null) {
        throw this.failureBeforeAction;
      }
      T result =
          action.doInTransaction(
              new org.springframework.transaction.support.SimpleTransactionStatus());
      if (this.failureAfterAction != null) {
        throw this.failureAfterAction;
      }
      return result;
    }

    void countAction() {
      this.actionsInsideTransaction++;
    }
  }

  private final class RecordingUserRepository implements UserRepository {

    private final List<User> saved = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong sequence =
        new java.util.concurrent.atomic.AtomicLong();

    /** id는 DB가 발급한다. 요약 응답이 id를 쓰므로 저장소 없이 도는 테스트에서는 여기서 채운다. */
    @Override
    public User save(User user) {
      UserRegistrationServiceTest.this.transaction.countAction();
      assignId(user, this.sequence.incrementAndGet());
      this.saved.add(user);
      return user;
    }

    private static void assignId(User user, long id) {
      try {
        java.lang.reflect.Field field = User.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(user, id);
      } catch (ReflectiveOperationException e) {
        // 엔티티에 setter를 만들지 않는다는 규칙을 테스트 편의로 깨지 않으려고 리플렉션을 쓴다.
        throw new IllegalStateException("테스트 fixture에 id를 넣지 못했습니다.", e);
      }
    }

    @Override
    public Optional<User> findById(Long id) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<User> findByUsername(String username) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<User> findByEmail(Email email) {
      throw new UnsupportedOperationException();
    }

    private boolean usernameTaken;
    private boolean emailTaken;
    private RuntimeException existsFailure;
    private int existsCallCount;

    @Override
    public boolean existsByUsername(String username) {
      this.existsCallCount++;
      if (this.existsFailure != null) {
        throw this.existsFailure;
      }
      return this.usernameTaken;
    }

    @Override
    public boolean existsByEmail(Email email) {
      this.existsCallCount++;
      if (this.existsFailure != null) {
        throw this.existsFailure;
      }
      return this.emailTaken;
    }

    @Override
    public int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
      throw new UnsupportedOperationException("회원가입 경로에서는 쓰지 않는다.");
    }

    @Override
    public int recordLoginFailure(
        long userId, int maxAttempts, LocalDateTime lockedUntil, LocalDateTime now) {
      throw new UnsupportedOperationException("회원가입 경로에서는 쓰지 않는다.");
    }

    @Override
    public int recordLoginSuccess(
        long userId, long expectedRowVersion, long expectedAuthVersion, LocalDateTime now) {
      throw new UnsupportedOperationException("회원가입 경로에서는 쓰지 않는다.");
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

  private final class RecordingMailOutboxRepository implements MailOutboxRepository {

    private final List<MailOutboxEntry> saved = new ArrayList<>();

    @Override
    public MailOutboxEntry save(MailOutboxEntry entry) {
      UserRegistrationServiceTest.this.transaction.countAction();
      this.saved.add(entry);
      return entry;
    }

    @Override
    public List<MailOutboxEntry> findClaimCandidates(
        LocalDateTime now, int maxAttempts, int limit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean claim(MailOutboxClaim claim, LocalDateTime now, int maxAttempts) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean markRetry(
        MailOutboxClaim claim,
        LocalDateTime nextAttemptAt,
        String lastErrorKind,
        LocalDateTime now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean markTerminal(
        MailOutboxClaim claim, MailDispatchStatus status, String lastErrorKind, LocalDateTime now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int failStale(LocalDateTime now, int maxAttempts) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int deleteTerminalCompletedBefore(LocalDateTime threshold) {
      throw new UnsupportedOperationException();
    }
  }
}
