package com.itda.user.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.domain.Username;
import com.itda.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 공개 회원가입 (API 명세 3.2·3.2.1절).
 *
 * <p>순서에 이유가 있다. 결정적인 입력 위반을 먼저 확정하고(외부 조회·DB 접근 없음), 그다음 비밀번호 정책을 완성하며, 마지막에야 MariaDB 트랜잭션을 연다.
 * 계약(3.2.1절)이 "결정적 결과를 장애보다 우선한다"고 정하므로 형식이 틀린 요청 때문에 유출 조회나 DB를 건드리지 않는다.
 *
 * <p>아이디·이메일 형식을 비밀번호보다 먼저 본다. 어차피 거부할 요청으로 외부 유출 조회를 호출하지 않기 위해서다. 두 422 사이의 우선순위는 계약이 정하지 않았고
 * 외부에서 관측되는 차이는 code뿐이다.
 *
 * <p><b>트랜잭션 경계와 예외 변환 경계를 분리한다.</b> {@code @Transactional} 메서드 안에서 예외를 잡으면 commit은 그 뒤에 일어나므로
 * commit 단계에서 드러나는 UNIQUE 충돌을 놓친다. 그래서 {@link TransactionOperations#execute}로 트랜잭션을 열고 <b>execute가
 * 반환된 뒤</b> 예외를 분류한다 — commit이 끝난 자리에서 판정한다.
 */
public class UserRegistrationService {

  /**
   * 회원가입 입력 (API 명세 3.2.1절).
   *
   * <p>세 값만 받는다. {@code role}·{@code status}·{@code emailVerified}·{@code level}·{@code
   * authVersion}·{@code rowVersion}은 서버가 정하므로 이 타입에 자리가 없다 — 클라이언트가 무엇을 보내든 생성되는 계정 값에 닿을 수 없다.
   *
   * <p>정규화·형식 검증을 여기서 하지 않는다. {@code null}과 원문을 그대로 받아 {@link UserRegistrationService}가 한 자리에서
   * 판정한다. 값 객체 생성 시점이 흩어지면 "무엇이 어떤 오류 code가 되는지"가 계층마다 달라진다.
   *
   * <p>{@code toString()}을 가린다. 비밀번호 원문과 개인정보가 들어 있어 로그·예외에 찍히면 안 된다. 출력 문자열은 통합 전 top-level 타입 이름을
   * 유지한다.
   */
  public record Command(String username, String email, String password) {

    @Override
    public String toString() {
      return "UserRegistrationCommand[REDACTED]";
    }
  }

  private final UserRepository userRepository;
  private final MailOutboxEnqueueService mailOutboxEnqueueService;
  private final NewPasswordService newPasswordService;
  private final TransactionOperations transactionOperations;
  private final Clock clock;

  public UserRegistrationService(
      UserRepository userRepository,
      MailOutboxEnqueueService mailOutboxEnqueueService,
      NewPasswordService newPasswordService,
      TransactionOperations transactionOperations,
      Clock clock) {
    this.userRepository = userRepository;
    this.mailOutboxEnqueueService = mailOutboxEnqueueService;
    this.newPasswordService = newPasswordService;
    this.transactionOperations = transactionOperations;
    this.clock = clock;
  }

  /**
   * 계정을 만들고 이메일 인증 메일 작업을 같은 트랜잭션으로 접수한다.
   *
   * @throws BusinessException 입력 위반·중복·저장소 장애. HTTP 매핑은 {@link ErrorCode}가 갖는다
   */
  public UserSummary register(Command command) {
    Username username = parseUsername(command.username());
    Email email = parseEmail(command.email());
    String passwordHash = hashPassword(command.password());

    return createInTransaction(username, email, passwordHash);
  }

  private static Username parseUsername(String rawUsername) {
    try {
      return Username.of(rawUsername);
    } catch (IllegalArgumentException e) {
      // 원인 예외를 붙이지 않는다. message에 값이 없더라도 예외 사슬은 로그로 흘러가는 경로가 하나 더 늘어난다.
      throw new BusinessException(ErrorCode.INVALID_ACCOUNT_INPUT);
    }
  }

  private static Email parseEmail(String rawEmail) {
    try {
      return Email.of(rawEmail);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.INVALID_ACCOUNT_INPUT);
    }
  }

  /**
   * 비밀번호 정책을 끝내고 해시까지 만든다. 트랜잭션을 열기 전이다 (API 명세 3.2.1·3.6.2절).
   *
   * <p>{@code null}은 정책 위반이 아니라 필수 입력 누락이므로 {@code INVALID_PASSWORD}가 아니라 {@code
   * INVALID_ACCOUNT_INPUT}이다. 정책 판정은 문자열이 있을 때만 의미가 있다.
   */
  private String hashPassword(String rawPassword) {
    if (rawPassword == null) {
      throw new BusinessException(ErrorCode.INVALID_ACCOUNT_INPUT);
    }
    NewPasswordService.Result result = this.newPasswordService.prepare(rawPassword);
    return switch (result) {
      case NewPasswordService.Result.Hashed hashed -> hashed.encodedPassword().value();
      case NewPasswordService.Result.Violated ignored ->
          throw new BusinessException(ErrorCode.INVALID_PASSWORD);
      // 유출 조회 실패는 위반으로 단정하지 않는다. AUTH_STATE_UNAVAILABLE로 바꾸지도 않는다 (3.2.1절).
      case NewPasswordService.Result.Unavailable ignored ->
          throw new BusinessException(ErrorCode.PASSWORD_POLICY_UNAVAILABLE);
    };
  }

  /**
   * {@code users}와 {@code mail_outbox}를 한 트랜잭션으로 커밋한다 (API 명세 3.2.1절).
   *
   * <p><b>사전 중복 조회를 하지 않는다.</b> 조회와 INSERT 사이에는 언제나 틈이 있어 정확성의 근거가 되지 못하고, 어차피 DB UNIQUE 제약이 최종
   * 권위다. 조회를 덧붙이면 같은 판정을 두 곳에서 하게 되고 그중 한쪽만 맞는 상태가 생긴다. 중복 여부를 확인하는 조회는 <b>실패한 뒤에만</b>, 그것도 어느 값
   * 때문이었는지 알아내기 위해서만 한다 ({@link #isIdentifierTaken}).
   */
  private UserSummary createInTransaction(Username username, Email email, String passwordHash) {
    LocalDateTime now = LocalDateTime.now(this.clock);
    try {
      return this.transactionOperations.execute(
          status -> {
            User saved =
                this.userRepository.save(
                    User.create(username.value(), passwordHash, UserRole.LEARNER, email, now));
            this.mailOutboxEnqueueService.enqueue(
                AccountActionPurpose.EMAIL_VERIFICATION, email.getValue(), now);
            return UserSummary.from(saved);
          });
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(
          isUniqueViolation(e) && isIdentifierTaken(username, email)
              ? ErrorCode.ACCOUNT_IDENTIFIER_UNAVAILABLE
              : ErrorCode.AUTH_STATE_UNAVAILABLE);
    } catch (DataAccessException | TransactionException e) {
      // 명령 실패·commit 실패·결과 불확실을 하나로 묶는다. 어느 쪽인지 추측해 성공으로 만들지 않는다 (3.6.2절).
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
  }

  /**
   * UNIQUE 제약 위반임이 <b>DB가 말해 줘서</b> 확정된 경우만 참이다.
   *
   * <p>제약 <b>이름</b>으로 판정하지 않는다. 이름은 스키마를 누가 언제 만들었는지에 따라 달라진다 — 같은 {@code users.username} UNIQUE가
   * 기존 DB에서는 Hibernate가 지은 {@code UKr43af9ap4edm43mmtq01oddj6}이고 새로 만든 DB에서는 다른 값일 수 있다. 이름을 코드에 적어
   * 두면 그 목록에 없는 DB에서 중복이 조용히 {@code 503}이 된다.
   *
   * <p>대신 방언이 채워 주는 {@link ConstraintKind}를 본다. {@code UNIQUE}면 이 트랜잭션은 <b>확실히</b> 실패해 rollback됐다 —
   * 결과가 불확실한 상황이 아니다. 어느 컬럼이었는지만 아직 모른다.
   *
   * <p>예외 message를 보지 않는다. MariaDB는 {@code Duplicate entry '...' for key '...'}처럼 <b>중복된 값 자체</b>를
   * message에 담으므로, 그 문자열을 판정에 쓰면 값이 로그·예외 사슬로 흘러갈 경로가 생긴다.
   */
  private static boolean isUniqueViolation(DataIntegrityViolationException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        return violation.getKind() == ConstraintKind.UNIQUE;
      }
    }
    return false;
  }

  /**
   * 확정된 UNIQUE 위반을 <b>어느 값 때문인지</b>까지 확인한다 (API 명세 3.2.1절).
   *
   * <p>이것은 "결과가 불확실하니 조회해서 409로 추측"하는 것이 아니다. 순서가 반대다 — DB가 UNIQUE 위반이라고 확정해 트랜잭션이 rollback된
   * <b>뒤에</b>, 그 위반이 아이디·이메일 때문이었는지만 확인한다. 이 시점에 우리 행은 없으므로 조회에 걸리는 값은 전부 남의 것이다.
   *
   * <p>확인되지 않으면 {@code 409}가 아니다. 경쟁하던 쪽이 그사이 rollback했거나 아이디·이메일이 아닌 다른 UNIQUE가 걸린 경우이며, 둘 다
   * {@code 503}으로 두는 편이 안전하다 — 재시도로 복구되고, 쓸 수 있는 아이디를 못 쓴다고 알려 주지 않는다.
   *
   * <p>대소문자 비구분 비교는 여기서 하지 않는다. 조회도 UNIQUE 제약과 같은 DB collation을 타므로 제약이 막은 것과 같은 기준으로 걸린다.
   */
  private boolean isIdentifierTaken(Username username, Email email) {
    try {
      return this.userRepository.existsByUsername(username.value())
          || this.userRepository.existsByEmail(email);
    } catch (DataAccessException | TransactionException e) {
      // 확인에 실패하면 중복이라고 단정하지 않는다. 모르는 것은 모르는 채로 503이다 (3.6.2절).
      return false;
    }
  }
}
