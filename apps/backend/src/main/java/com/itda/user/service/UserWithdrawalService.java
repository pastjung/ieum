package com.itda.user.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserStatus;
import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 회원 탈퇴 (API 명세 3.1·3.2.1·3.4·3.6.2절, ERD 3.1절).
 *
 * <p><b>순서가 계약이다.</b> 입력 → 사용자 상태 → 현재 비밀번호 확인 → 폐기 비밀번호 해시 계산을 트랜잭션 밖에서 끝낸 다음 조건부 UPDATE 한 번으로 탈퇴를
 * 확정하고, 그 commit 뒤에 Redis Refresh 계열을 정리한다.
 *
 * <ul>
 *   <li>Argon2id 검증과 해시는 트랜잭션과 행 잠금 전에 계산한다. 잠금 안에서 계산하면 같은 계정의 로그인·재설정이 그동안 직렬화된다
 *   <li>행을 지우지 않는다. 과거 학습·수업 기록은 {@code users.id}를 계속 참조하고, 원래 username·email은 가명으로 비워 즉시 재가입에 쓸 수
 *       있게 한다
 *   <li>기존 Access·Refresh 무효화의 권위는 같은 UPDATE가 올리는 {@code status}와 {@code auth_version}이다. Redis 계열
 *       정리는 사후 정리라 실패해도 탈퇴를 되돌리거나 응답을 바꾸지 않는다
 * </ul>
 *
 * <p>플랫폼·쿠키를 알지 않고 토큰을 발급하지 않는다. 사용자 식별자는 인증 필터가 확인한 principal에서만 온다.
 */
public class UserWithdrawalService {

  /**
   * 회원 탈퇴 입력 (API 명세 3.2.1절).
   *
   * <p>{@code userId}는 요청 body·path·query가 아니라 인증된 principal에서 온다. {@code currentPassword}는 가공하지 않은
   * 원문이다.
   *
   * <p>{@code toString()}을 가린다 — 현재 비밀번호 원문과 사용자 식별자가 들어 있다.
   */
  public record Command(long userId, String currentPassword) {

    @Override
    public String toString() {
      return "UserWithdrawalCommand[REDACTED]";
    }
  }

  /**
   * 가명이 다른 행의 UNIQUE 값과 겹쳤을 때 새 가명으로 시도하는 총 횟수.
   *
   * <p>122비트 난수라 실제 충돌은 사실상 일어나지 않는다. 그래도 가입 경로가 같은 모양의 이메일을 받을 수 있어 충돌을 성공이나 원래 식별자 중복으로 오인하지 않게
   * 하고, 무제한으로 반복하지 않도록 작은 상한을 둔다.
   */
  static final int MAX_PSEUDONYM_ATTEMPTS = 3;

  /** 가명 username 접두사. 가입 username 상한(30자)을 넘는 길이라 공개 가입으로는 같은 값을 만들 수 없다. */
  static final String PSEUDONYM_PREFIX = "withdrawn-";

  /** 메일이 전달되지 않는 예약 최상위 도메인(RFC 2606 {@code .invalid})을 쓴다. 실제 운영 도메인을 쓰지 않는다. */
  static final String PSEUDONYM_EMAIL_DOMAIN = "withdrawn.invalid";

  /** 폐기 비밀번호의 원천 난수 길이. 256비트다. */
  private static final int DISCARDED_SECRET_BYTES = 32;

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private static final Logger log = LoggerFactory.getLogger(UserWithdrawalService.class);

  private final UserRepository userRepository;
  private final PasswordVerificationService passwordVerificationService;
  private final PasswordHasher passwordHasher;
  private final RefreshTokenStore refreshTokenStore;
  private final TransactionOperations transactionOperations;
  private final Supplier<UUID> pseudonymSource;
  private final Clock clock;

  public UserWithdrawalService(
      UserRepository userRepository,
      PasswordVerificationService passwordVerificationService,
      PasswordHasher passwordHasher,
      RefreshTokenStore refreshTokenStore,
      TransactionOperations transactionOperations,
      Clock clock) {
    this(
        userRepository,
        passwordVerificationService,
        passwordHasher,
        refreshTokenStore,
        transactionOperations,
        UUID::randomUUID,
        clock);
  }

  /**
   * 가명 원천을 바꿔 끼울 수 있는 생성자. 가명 UNIQUE 충돌을 결정적으로 재현하는 테스트만 쓴다.
   *
   * @param pseudonymSource 호출마다 새 UUID를 돌려줘야 한다. 운영은 CSPRNG 기반 {@link UUID#randomUUID()}다
   */
  UserWithdrawalService(
      UserRepository userRepository,
      PasswordVerificationService passwordVerificationService,
      PasswordHasher passwordHasher,
      RefreshTokenStore refreshTokenStore,
      TransactionOperations transactionOperations,
      Supplier<UUID> pseudonymSource,
      Clock clock) {
    this.userRepository = userRepository;
    this.passwordVerificationService = passwordVerificationService;
    this.passwordHasher = passwordHasher;
    this.refreshTokenStore = refreshTokenStore;
    this.transactionOperations = transactionOperations;
    this.pseudonymSource = pseudonymSource;
    this.clock = clock;
  }

  /**
   * 현재 비밀번호를 확인하고 탈퇴를 확정한다. 정상 반환은 MariaDB commit이 확정됐다는 뜻이다.
   *
   * @throws BusinessException 입력 누락은 {@code INVALID_ACCOUNT_INPUT}, 인증할 수 없는 사용자는 {@code
   *     UNAUTHORIZED}, 비밀번호 불일치는 {@code CURRENT_PASSWORD_MISMATCH}, 저장소 장애·결과 불확실·인증 상태 경합은 {@code
   *     AUTH_STATE_UNAVAILABLE}
   */
  public void withdraw(Command command) {
    String currentPassword = requirePresent(command.currentPassword());
    User snapshot = findAuthenticatableUser(command.userId());
    if (!this.passwordVerificationService.matches(currentPassword, snapshot.getPasswordHash())) {
      // 불일치면 가명도 폐기 해시도 만들지 않고 어떤 저장소도 바꾸지 않는다.
      throw new BusinessException(ErrorCode.CURRENT_PASSWORD_MISMATCH);
    }
    String discardedPasswordHash = discardedPasswordHash();

    withdrawWithPseudonyms(command.userId(), snapshot, discardedPasswordHash);
    revokeRefreshFamilies(command.userId());
  }

  /**
   * 누락·null·빈 문자열만 {@code INVALID_ACCOUNT_INPUT}이다 (3.2.1절).
   *
   * <p>공백만 있는 non-empty 값은 required 위반이 아니다. 비밀번호는 공백을 값의 일부로 허용하므로 저장된 비밀번호와 비교한 결과를 따른다. trim하지
   * 않는다.
   */
  private static String requirePresent(String currentPassword) {
    if (currentPassword == null || currentPassword.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_ACCOUNT_INPUT);
    }
    return currentPassword;
  }

  /**
   * 인증 필터 통과 뒤 다시 읽는다. 그 사이 탈퇴·상태 변경이 commit됐을 수 있다.
   *
   * <p>여기서 읽은 {@code row_version}·{@code password_hash}·{@code auth_version}이 조건부 UPDATE의 Snapshot이
   * 된다. 조건은 내 정보 조회와 같다 — 인증 가능한 계정이 아니면 원인을 구분하지 않고 {@code 401}이다.
   */
  private User findAuthenticatableUser(long userId) {
    return findUser(userId)
        .filter(UserWithdrawalService::isAuthenticatable)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
  }

  private Optional<User> findUser(long userId) {
    try {
      return this.userRepository.findById(userId);
    } catch (DataAccessException | TransactionException e) {
      // 조회 장애를 "사용자 없음"으로 접으면 장애가 401로 위장된다 (3.6.2절 fail-closed).
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
  }

  private static boolean isAuthenticatable(User user) {
    return user.getStatus() == UserStatus.ACTIVE && user.isEmailVerified();
  }

  /**
   * 아무도 모르는 256비트 비밀을 기존 Argon2id 설정으로 해시한다.
   *
   * <p>새 비밀번호 정책과 유출 조회를 거치지 않는다 — 사람이 고른 값이 아니다. 원천 바이트는 인코딩 직후 지우고, 문자열은 이 메서드 밖으로 내보내지 않는다.
   */
  private String discardedPasswordHash() {
    byte[] secret = new byte[DISCARDED_SECRET_BYTES];
    SECURE_RANDOM.nextBytes(secret);
    String encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    Arrays.fill(secret, (byte) 0);
    return this.passwordHasher.hash(encodedSecret).value();
  }

  /**
   * 조건부 UPDATE를 실행한다. 가명 UNIQUE 충돌만 새 가명으로 제한된 횟수 다시 시도한다.
   *
   * <p>인증 Snapshot 경합(0행)은 재시도하지 않는다. 확인에 쓴 상태가 바뀌었으면 이전 확인 결과로 탈퇴시키지 않고, 계약에 없는 재시도를 만들지 않는다.
   */
  private void withdrawWithPseudonyms(long userId, User snapshot, String discardedPasswordHash) {
    for (int attempt = 0; attempt < MAX_PSEUDONYM_ATTEMPTS; attempt++) {
      switch (update(userId, snapshot, newPseudonym(), discardedPasswordHash)) {
        case WITHDRAWN -> {
          return;
        }
        case NOT_UPDATED -> throw notUpdated(userId);
        case PSEUDONYM_TAKEN -> {
          // rollback이 확정된 가명 충돌이다. 우리 행은 바뀌지 않았으므로 새 가명으로 다시 시도한다.
        }
      }
    }
    // 상한까지 충돌했다. 원래 식별자 중복으로 오인하지 않고 장애로 끝낸다.
    throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  /** UPDATE 한 번의 결과. 서비스 안에서만 쓴다. */
  private enum UpdateOutcome {
    WITHDRAWN,
    NOT_UPDATED,
    PSEUDONYM_TAKEN
  }

  /**
   * 조건부 UPDATE 한 번. commit까지 끝나고 나서 결과를 판정한다.
   *
   * <p>예외 분류를 {@code execute} 밖에 두는 이유는 회원가입과 같다 — 트랜잭션 안에서 잡으면 commit 실패를 성공으로 돌려주게 된다. {@code
   * DataIntegrityViolationException}이 {@code execute} 밖으로 나왔다면 문장이 실패했고 rollback도 끝났다. rollback이
   * 실패했다면 {@code TransactionException}으로 나오므로 결과 불확실로 분류된다.
   */
  private UpdateOutcome update(
      long userId, User snapshot, Pseudonym pseudonym, String discardedPasswordHash) {
    LocalDateTime now = LocalDateTime.now(this.clock);
    try {
      Integer updated =
          this.transactionOperations.execute(
              status ->
                  this.userRepository.withdraw(
                      userId,
                      snapshot.getRowVersion(),
                      snapshot.getPasswordHash(),
                      snapshot.getAuthVersion(),
                      pseudonym.username(),
                      pseudonym.email(),
                      discardedPasswordHash,
                      now));
      return updated != null && updated > 0 ? UpdateOutcome.WITHDRAWN : UpdateOutcome.NOT_UPDATED;
    } catch (DataIntegrityViolationException e) {
      // UPDATE가 새로 쓰는 UNIQUE 값은 가명뿐이다. 그 밖의 무결성 위반은 추측하지 않고 장애로 둔다. 원인 예외와 값을 남기지 않는다.
      if (DataIntegrityViolations.isUniqueViolation(e)) {
        return UpdateOutcome.PSEUDONYM_TAKEN;
      }
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    } catch (DataAccessException | TransactionException e) {
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
  }

  /**
   * 0행을 다시 읽어 분류한다. 어떤 경우에도 성공으로 숨기지 않는다.
   *
   * <p>인증할 수 없는 상태가 됐으면 {@code 401}, 인증 가능하지만 {@code password_hash}·{@code auth_version}·{@code
   * row_version} 중 무엇이든 Snapshot과 달라졌으면 {@code 503}이다. 재조회 자체의 장애도 {@code 503}이다.
   */
  private BusinessException notUpdated(long userId) {
    User current = findUser(userId).filter(UserWithdrawalService::isAuthenticatable).orElse(null);
    if (current == null) {
      return new BusinessException(ErrorCode.UNAUTHORIZED);
    }
    return new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  /** 원래 username·email과 연결되지 않는 비결정적 가명을 만든다. 원래 값을 입력으로 쓰지 않는다. */
  private Pseudonym newPseudonym() {
    String token = this.pseudonymSource.get().toString().replace("-", "");
    return new Pseudonym(
        PSEUDONYM_PREFIX + token,
        Email.of(PSEUDONYM_PREFIX + token + "@" + PSEUDONYM_EMAIL_DOMAIN));
  }

  /** 탈퇴 가명. 외부 응답·로그로 나가지 않도록 {@code toString()}을 가린다. */
  private record Pseudonym(String username, Email email) {

    @Override
    public String toString() {
      return "WithdrawalPseudonym[REDACTED]";
    }
  }

  /**
   * MariaDB commit이 확정된 뒤의 사후 정리다 (3.6.2절 「Refresh family 사후 정리 실패」).
   *
   * <p>정리 결과는 응답을 바꾸지 않는다. 기존 Refresh는 이미 {@code status}·{@code auth_version}으로 거부된다. 예외까지 삼키는 이유는
   * 여기서 새어 나간 예외가 확정된 탈퇴를 {@code 500}으로 보고하게 만들기 때문이다. 로그에는 실패 단계만 남기고 사용자·계열 식별자·키·예외 원문을 넣지 않는다.
   */
  private void revokeRefreshFamilies(long userId) {
    RefreshTokenStore.AllFamiliesRevocationResult result;
    try {
      result = this.refreshTokenStore.revokeAllFamilies(userId);
    } catch (RuntimeException e) {
      logCleanupIncomplete("exception");
      return;
    }
    if (result != RefreshTokenStore.AllFamiliesRevocationResult.REVOKED) {
      logCleanupIncomplete("unavailable");
    }
  }

  private static void logCleanupIncomplete(String stage) {
    log.warn("회원 탈퇴 뒤 Refresh 계열 정리를 끝내지 못했습니다. api=user-withdrawal stage={}", stage);
  }
}
