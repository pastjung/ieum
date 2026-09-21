package com.itda.user.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.User;
import com.itda.user.domain.UserStatus;
import com.itda.user.repository.ActionTokenStore;
import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 비밀번호 재설정 완료 (API 명세 3.4.2·3.6.2절).
 *
 * <p><b>순서가 계약이다.</b> 형식 → JWT → 용도 → 사용자 상태 → Redis 사전 조회 → 비밀번호 정책 → Argon2id를 전부 끝낸 다음에야 Redis에서
 * 토큰을 소비하고, 마지막에 MariaDB를 바꾼다. 기존 Refresh 계열 정리는 그 commit 뒤의 사후 정리다.
 *
 * <ul>
 *   <li>Redis 사전 조회는 인증 없이 호출되는 이 endpoint에서 이미 소비·무효화된 정상 서명 토큰이 유출 조회와 Argon2id를 반복 유발하지 못하게 막는
 *       자원 보호용이다. 정확성의 최종 근거는 원자적 소비다
 *   <li>정책 위반과 유출 조회 장애는 소비 전에 끝난다. 잘못된 새 비밀번호 때문에 30분짜리 정상 토큰이 소진되면 안 된다
 *   <li>Argon2id는 트랜잭션과 행 잠금 전에 계산한다. 잠금 안에서 계산하면 같은 계정의 로그인·탈퇴가 그동안 직렬화된다
 *   <li>Redis를 먼저 소비한다. DB를 먼저 바꾸면 소비 실패 시 같은 토큰으로 비밀번호를 한 번 더 바꿀 수 있다
 * </ul>
 *
 * <p>두 저장소를 한 트랜잭션으로 묶지 않고 소비한 토큰을 되돌리는 보상도 하지 않는다. 기존 Access·Refresh 무효화의 권위는 같은 트랜잭션에서 올리는 {@code
 * users.auth_version}이며, Redis 계열 정리가 실패해도 그 효력은 되돌아가지 않는다.
 *
 * <p>플랫폼·쿠키를 알지 않고 토큰을 발급하지 않는다. 자동 로그인은 없다.
 */
public class PasswordResetService {

  /**
   * 비밀번호 재설정 완료 입력 (API 명세 3.4.2절).
   *
   * <p>두 값 모두 가공하지 않은 원문이다. token은 opaque 값이라 trim하지 않고, 비밀번호는 앞뒤 공백도 값의 일부다.
   *
   * <p>{@code toString()}을 가린다 — 토큰 원문과 새 비밀번호가 들어 있다.
   *
   * @param token 문자열이 아니거나 없으면 {@code null}
   * @param newPassword 문자열이 아니거나 없으면 {@code null}
   */
  public record Command(String token, String newPassword) {

    @Override
    public String toString() {
      return "PasswordResetCommand[REDACTED]";
    }
  }

  private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

  private final PasswordResetTokenVerifier tokenVerifier;
  private final ActionTokenStore actionTokenStore;
  private final UserRepository userRepository;
  private final NewPasswordService newPasswordService;
  private final RefreshTokenStore refreshTokenStore;
  private final TransactionOperations transactionOperations;
  private final Clock clock;

  public PasswordResetService(
      PasswordResetTokenVerifier tokenVerifier,
      ActionTokenStore actionTokenStore,
      UserRepository userRepository,
      NewPasswordService newPasswordService,
      RefreshTokenStore refreshTokenStore,
      TransactionOperations transactionOperations,
      Clock clock) {
    this.tokenVerifier = tokenVerifier;
    this.actionTokenStore = actionTokenStore;
    this.userRepository = userRepository;
    this.newPasswordService = newPasswordService;
    this.refreshTokenStore = refreshTokenStore;
    this.transactionOperations = transactionOperations;
    this.clock = clock;
  }

  /**
   * 토큰을 소비하고 비밀번호를 바꾼다. 정상 반환은 MariaDB 변경이 commit됐다는 뜻이다.
   *
   * @throws BusinessException 형식 위반은 {@code INVALID_ACCOUNT_INPUT}, 토큰·사용자 부적격은 {@code
   *     ACTION_TOKEN_INVALID}, 정책 위반은 {@code INVALID_PASSWORD}, 유출 조회 장애는 {@code
   *     PASSWORD_POLICY_UNAVAILABLE}, 저장소 장애·결과 불확실·지속 경합은 {@code AUTH_STATE_UNAVAILABLE}
   */
  public void complete(Command command) {
    requirePresent(command);
    ActionTokenVerification.Verified verified = verify(command.token());
    User snapshot = findActiveUser(verified.userId());
    ActionTokenStore.TokenRecord submitted = recordOf(verified);
    requireEligible(submitted);

    PasswordHasher.EncodedPassword newPassword = prepare(command.newPassword());
    consume(submitted);

    changePassword(verified.userId(), snapshot, newPassword);
    revokeRefreshFamilies(verified.userId());
  }

  /**
   * 1단계. 여기만 {@code INVALID_ACCOUNT_INPUT}이다 — 요청이 계약 형식을 갖추지 못한 경우이며 토큰·계정 상태와 무관하다.
   *
   * <p>token은 {@code isBlank()}로 빈 문자열과 공백만 있는 값을 거른다. 새 비밀번호는 존재만 본다. 빈 값과 공백 값은 필드 누락이 아니라 비밀번호
   * 정책으로 판정할 값이다 (3.4.2절).
   */
  private static void requirePresent(Command command) {
    if (command.token() == null || command.token().isBlank() || command.newPassword() == null) {
      throw new BusinessException(ErrorCode.INVALID_ACCOUNT_INPUT);
    }
  }

  /** 2·3단계. JOSE Header·서명·claim은 port가, 용도는 여기서 한 번 더 고정한다. 원문을 가공하지 않고 넘긴다. */
  private ActionTokenVerification.Verified verify(String rawToken) {
    if (!(this.tokenVerifier.verify(rawToken) instanceof ActionTokenVerification.Verified verified)
        || verified.purpose() != AccountActionPurpose.PASSWORD_RESET) {
      throw new BusinessException(ErrorCode.ACTION_TOKEN_INVALID);
    }
    return verified;
  }

  /**
   * 4단계. 여기서 읽은 {@code row_version}·{@code password_hash}·{@code auth_version}이 뒤따르는 조건부 갱신의
   * Snapshot이 된다.
   *
   * <p>탈퇴는 {@code password_hash}를 폐기된 무작위 값으로 바꾸므로, 비ACTIVE 계정의 재설정을 허용하면 로그인 가능한 계정이 되살아난다.
   */
  private User findActiveUser(long userId) {
    return findUser(userId)
        .filter(user -> user.getStatus() == UserStatus.ACTIVE)
        .orElseThrow(() -> new BusinessException(ErrorCode.ACTION_TOKEN_INVALID));
  }

  private Optional<User> findUser(long userId) {
    try {
      return this.userRepository.findById(userId);
    } catch (DataAccessException | TransactionException e) {
      // 조회 장애를 "사용자 없음"으로 접으면 장애가 400으로 위장된다 (3.6.2절 fail-closed).
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
  }

  private static ActionTokenStore.TokenRecord recordOf(ActionTokenVerification.Verified verified) {
    return new ActionTokenStore.TokenRecord(
        verified.userId(),
        verified.purpose(),
        verified.jti(),
        verified.tokenHash(),
        verified.expiresAtEpochSecond());
  }

  /**
   * 5단계. 상태를 바꾸지 않는 사전 조회다.
   *
   * <p>{@code action-current} 일치·{@code ACTIVE}·{@code tokenHash} 일치를 확인하지 못하면 유출 조회와 Argon2id에
   * 도달하지 않는다. 조회 명령 자체가 실패하면 부적격으로 단정하지 않고 {@code 503}이다.
   */
  private void requireEligible(ActionTokenStore.TokenRecord submitted) {
    switch (this.actionTokenStore.lookup(submitted)) {
      case ACTIVE -> {
        // 자원 보호용 확인만 끝났다. 소비 가능 여부는 원자적 소비가 다시 판정한다.
      }
      case UNAVAILABLE -> throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
      // NOT_FOUND·NOT_CURRENT·CONSUMED·INVALIDATED·HASH_MISMATCH·MISMATCHED는 전부 결정적 부적격이다.
      default -> throw new BusinessException(ErrorCode.ACTION_TOKEN_INVALID);
    }
  }

  /**
   * 6·7단계. NFC 정규화·code point 길이·흔한 값·유출 값 검사와 Argon2id 해시를 기존 {@link NewPasswordService}에 맡긴다.
   *
   * <p>기존 비밀번호와 같은지는 검사하지 않는다 (3.4.2절). 비교에 Argon2id 검증을 한 번 더 쓰는 비용에 비해 직전 값 하나만 막는 효과가 작다.
   */
  private PasswordHasher.EncodedPassword prepare(String rawNewPassword) {
    return switch (this.newPasswordService.prepare(rawNewPassword)) {
      case NewPasswordService.Result.Hashed hashed -> hashed.encodedPassword();
      case NewPasswordService.Result.Violated violated ->
          throw new BusinessException(ErrorCode.INVALID_PASSWORD);
      case NewPasswordService.Result.Unavailable unavailable ->
          // 위반으로 단정하지 않는다. 토큰을 소비하기 전이라 같은 입력으로 다시 시도할 수 있다 (3.6.2절).
          throw new BusinessException(ErrorCode.PASSWORD_POLICY_UNAVAILABLE);
    };
  }

  /**
   * 8단계. 적격 조건 확인과 {@code CONSUMED} 전이가 한 원자 연산에서 일어난다.
   *
   * <p>결과를 알 수 없으면 MariaDB를 <b>시작하지 않는다</b>. 실제로 소비됐을 수도, 아닐 수도 있으므로 어느 쪽인지 추측해 진행하지 않는다.
   */
  private void consume(ActionTokenStore.TokenRecord submitted) {
    switch (this.actionTokenStore.consume(submitted)) {
      case CONSUMED -> {
        // 소비 확정. 여기서부터 실패해도 토큰을 되돌리지 않는다.
      }
      case UNAVAILABLE -> throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
      // 사전 조회 뒤 다른 요청이 먼저 소비했거나 재발급으로 밀려났다. 전부 결정적 부적격이다.
      default -> throw new BusinessException(ErrorCode.ACTION_TOKEN_INVALID);
    }
  }

  /**
   * 9단계. 조건부 갱신 한 번, 어긋나면 재조회 후 최대 <b>한 번</b>만 더 시도한다 (3.6.2절).
   *
   * <p>재시도는 {@code status}·{@code password_hash}·{@code auth_version}이 Snapshot 그대로이고 {@code
   * row_version}만 달라졌을 때뿐이다. 그때는 이미 계산한 Argon2id 해시를 그대로 쓴다 — salt와 파라미터까지 담긴 저장 가능한 결과라 같은 새 비밀번호를
   * 나타낸다. 인증 필드가 바뀌었다면 그 사이 다른 인증 변경이 확정된 것이므로 덮어쓰지 않는다.
   *
   * <p>토큰은 이미 소비됐고 실패해도 복원되지 않는다. 어떤 경합도 성공으로 숨기지 않는다.
   */
  private void changePassword(
      long userId, User snapshot, PasswordHasher.EncodedPassword newPassword) {
    if (update(userId, snapshot.getRowVersion(), snapshot, newPassword)) {
      return;
    }

    User current =
        findUser(userId).orElseThrow(() -> new BusinessException(ErrorCode.ACTION_TOKEN_INVALID));
    if (current.getStatus() != UserStatus.ACTIVE) {
      throw new BusinessException(ErrorCode.ACTION_TOKEN_INVALID);
    }
    if (!current.getPasswordHash().equals(snapshot.getPasswordHash())
        || current.getAuthVersion() != snapshot.getAuthVersion()) {
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
    if (!update(userId, current.getRowVersion(), snapshot, newPassword)) {
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
  }

  /**
   * 조건부 갱신 한 번. commit까지 끝나고 나서 결과를 판정한다.
   *
   * <p>예외 분류를 {@code execute} 밖에 두는 이유는 회원가입과 같다 — 트랜잭션 안에서 잡으면 commit 실패를 성공으로 돌려주게 된다.
   */
  private boolean update(
      long userId,
      long expectedRowVersion,
      User snapshot,
      PasswordHasher.EncodedPassword newPassword) {
    LocalDateTime now = LocalDateTime.now(this.clock);
    try {
      Integer updated =
          this.transactionOperations.execute(
              status ->
                  this.userRepository.resetPassword(
                      userId,
                      expectedRowVersion,
                      snapshot.getPasswordHash(),
                      snapshot.getAuthVersion(),
                      newPassword.value(),
                      now));
      return updated != null && updated > 0;
    } catch (DataAccessException | TransactionException e) {
      // 소비는 확정됐지만 DB가 실패했거나 commit 결과를 알 수 없다. 204로 만들지 않고 토큰도 복원하지 않는다 (3.6.2절).
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
  }

  /**
   * 10단계. MariaDB commit이 확정된 뒤의 사후 정리다 (3.6.2절 「Refresh family 사후 정리 실패」).
   *
   * <p>정리 결과는 응답을 바꾸지 않는다. 기존 Refresh는 이미 {@code auth_version} 불일치로 거부되므로, 여기서 실패를 {@code 503}으로
   * 올리면 이미 바뀐 비밀번호를 실패로 보고하게 된다.
   *
   * <p>예외도 삼키는 이유는 같다. 이 지점에서 새어 나간 예외는 commit된 변경을 {@code 500}으로 보고하게 만든다. 로그에는 실패 단계만 남기고 사용자
   * 식별자·계열 식별자·키·예외 원문을 넣지 않는다.
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
    log.warn("비밀번호 재설정 뒤 Refresh 계열 정리를 끝내지 못했습니다. api=password-reset stage={}", stage);
  }
}
