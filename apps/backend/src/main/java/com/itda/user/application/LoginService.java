package com.itda.user.application;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.application.port.out.AccessTokenClaims;
import com.itda.user.application.port.out.AccessTokenIssuer;
import com.itda.user.application.port.out.IssuedAccessToken;
import com.itda.user.application.port.out.IssuedRefreshToken;
import com.itda.user.application.port.out.RefreshFamilyIssueResult;
import com.itda.user.application.port.out.RefreshTokenClaims;
import com.itda.user.application.port.out.RefreshTokenIssuer;
import com.itda.user.application.port.out.RefreshTokenRecord;
import com.itda.user.application.port.out.RefreshTokenStore;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRepository;
import com.itda.user.domain.UserStatus;
import com.itda.user.domain.Username;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 로그인 (API 명세 3.3.1·3.6.2절, ERD 3.1절).
 *
 * <p><b>플랫폼을 알지 않는다.</b> WEB·MOBILE은 같은 세션을 다른 방식으로 전달할 뿐이고, 그 분기는 presentation의 몫이다.
 *
 * <p>실패 원인을 밖으로 구분하지 않는다. 계정 없음·비밀번호 오류·탈퇴·잠금이 모두 {@code 401 INVALID_CREDENTIALS} 하나다. 자격 증명이 맞는데
 * 이메일만 미인증인 경우에만 {@code 403}으로 갈라진다 — 이미 비밀번호를 아는 사람에게만 드러나는 정보라서다.
 *
 * <p>순서에 이유가 있다. 입력 형식을 먼저 확정하고, 사용자를 한 번의 Snapshot으로 읽고, Argon2id를 트랜잭션 밖에서 수행한 뒤, 조건부 UPDATE로 그
 * Snapshot이 아직 유효한지 확인하고, 그 commit이 확정된 다음에야 토큰을 만든다. 마지막으로 Redis 등록이 확정돼야 결과를 돌려준다.
 *
 * <p>MariaDB와 Redis는 하나의 트랜잭션이 아니다. 실패 상태 초기화가 커밋된 뒤 Redis 등록이 실패할 수 있으며, 그때는 토큰을 돌려주지 않고 {@code
 * 503}으로 끊는다. 되돌리기 위한 분산 트랜잭션을 추가하지 않는다 (3.6.2절).
 */
public class LoginService {

  /** family 등록 시도 상한. 재시도는 {@code ALREADY_EXISTS}에만 쓰고 무제한 반복하지 않는다 (3.6.1절). */
  private static final int FAMILY_ISSUE_ATTEMPTS = 2;

  private final UserRepository userRepository;
  private final PasswordVerificationService passwordVerificationService;
  private final DecoyPasswordHash decoyPasswordHash;
  private final AccessTokenIssuer accessTokenIssuer;
  private final RefreshTokenIssuer refreshTokenIssuer;
  private final RefreshTokenStore refreshTokenStore;
  private final TransactionOperations transactionOperations;
  private final Clock clock;

  public LoginService(
      UserRepository userRepository,
      PasswordVerificationService passwordVerificationService,
      DecoyPasswordHash decoyPasswordHash,
      AccessTokenIssuer accessTokenIssuer,
      RefreshTokenIssuer refreshTokenIssuer,
      RefreshTokenStore refreshTokenStore,
      TransactionOperations transactionOperations,
      Clock clock) {
    this.userRepository = userRepository;
    this.passwordVerificationService = passwordVerificationService;
    this.decoyPasswordHash = decoyPasswordHash;
    this.accessTokenIssuer = accessTokenIssuer;
    this.refreshTokenIssuer = refreshTokenIssuer;
    this.refreshTokenStore = refreshTokenStore;
    this.transactionOperations = transactionOperations;
    this.clock = clock;
  }

  /**
   * 자격 증명을 검증하고 인증 세션을 만든다.
   *
   * @throws BusinessException 형식 위반은 {@code INVALID_ACCOUNT_INPUT}, 자격 증명 실패는 {@code
   *     INVALID_CREDENTIALS}, 이메일 미인증은 {@code EMAIL_VERIFICATION_REQUIRED}, 저장소 장애·결과 불확실은 {@code
   *     AUTH_STATE_UNAVAILABLE}
   */
  public AuthSession login(LoginCommand command) {
    Username username = parseUsername(command.username());
    String password = requirePassword(command.password());
    LocalDateTime now = LocalDateTime.now(this.clock);

    User snapshot = findByUsername(username).orElse(null);
    if (snapshot == null) {
      // 없는 계정에도 같은 검증을 한 번 수행한다. 곧장 실패로 끝내면 응답 속도만으로 계정 존재가 드러난다.
      this.passwordVerificationService.matches(password, this.decoyPasswordHash.value());
      throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
    }
    if (!this.passwordVerificationService.matches(password, snapshot.getPasswordHash())) {
      recordFailure(snapshot.getId(), now);
      throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
    }
    requireLoginAllowed(snapshot, now);

    // 조건부 갱신에 실제로 성공한 사용자 상태로 세션을 만든다. 경합 재시도에서는 재조회한 쪽이다.
    return issueSession(confirmSnapshotAndResetFailures(snapshot, now), snapshot.getAuthVersion());
  }

  // ---------- 입력 ----------

  private static Username parseUsername(String rawUsername) {
    try {
      return Username.of(rawUsername);
    } catch (IllegalArgumentException e) {
      // 원인 예외를 붙이지 않는다. message에 값이 없더라도 예외 사슬은 로그로 흘러가는 경로가 하나 더 늘어난다.
      throw new BusinessException(ErrorCode.INVALID_ACCOUNT_INPUT);
    }
  }

  /** 비밀번호는 trim하지 않는다. 공백도 값의 일부이며 빈 문자열만 required 위반이다 (3.3.1절). */
  private static String requirePassword(String rawPassword) {
    if (rawPassword == null || rawPassword.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_ACCOUNT_INPUT);
    }
    return rawPassword;
  }

  // ---------- 조회와 판정 ----------

  private Optional<User> findByUsername(Username username) {
    try {
      // 대소문자 비구분 비교는 MariaDB collation이 한다. 애플리케이션이 표기를 바꾸지 않는다 (ERD 3.1절).
      return this.userRepository.findByUsername(username.value());
    } catch (DataAccessException | TransactionException e) {
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
  }

  private Optional<User> findById(long userId) {
    try {
      return this.userRepository.findById(userId);
    } catch (DataAccessException | TransactionException e) {
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
  }

  /**
   * 비밀번호가 맞는 계정에만 적용한다.
   *
   * <p>탈퇴·잠금은 {@code 401}로 일반화하고, 이메일 미인증만 {@code 403}이다. 이 순서가 뒤바뀌면 탈퇴 계정이 {@code 403}을 받아 계정 상태가
   * 드러난다.
   */
  private static void requireLoginAllowed(User user, LocalDateTime now) {
    if (user.getStatus() != UserStatus.ACTIVE || user.isLocked(now)) {
      throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
    }
    if (!user.isEmailVerified()) {
      throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
    }
  }

  // ---------- 실패 누적 ----------

  /**
   * 실패를 원자적으로 기록한다 (ERD 3.1절).
   *
   * <p>0행은 장애가 아니다 — DB가 정상 응답했고 계정이 ACTIVE가 아니거나 이미 잠겨 있다는 결정적 결과다. 그대로 {@code 401}로 간다. 반대로 명령
   * 자체가 실패하면 카운터를 잃은 채 {@code 401}만 주지 않는다. 그러면 실패를 반복해 잠금을 우회할 수 있다.
   */
  private void recordFailure(long userId, LocalDateTime now) {
    try {
      this.transactionOperations.execute(
          status ->
              this.userRepository.recordLoginFailure(
                  userId, User.MAX_FAILED_LOGIN_ATTEMPTS, now.plus(User.LOGIN_LOCK_DURATION), now));
    } catch (DataAccessException | TransactionException e) {
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
  }

  // ---------- Snapshot 재확인 ----------

  /**
   * Argon2id 검증에 쓴 Snapshot이 아직 유효한지 조건부 UPDATE로 확인하면서 실패 상태를 초기화한다 (ERD 3.1절, 3.6.2절).
   *
   * <p>0행이면 사용자를 다시 읽어 원인을 가른다. {@code password_hash}나 {@code auth_version}이 달라졌다면 구 자격 증명으로 세션을
   * 만들지 않는다. {@code row_version}만 달라졌다면 최신 값으로 <b>한 번만</b> 다시 시도하고, 그때도 어긋나면 지속 경합으로 보고 {@code
   * 503}이다.
   *
   * <p>재시도에서 Argon2id를 다시 실행하지 않는다. {@code password_hash}가 같음을 확인했으므로 이미 끝난 검증이다.
   *
   * @return 갱신에 <b>실제로 성공한</b> 사용자 상태. 첫 시도면 최초 Snapshot, 경합 재시도면 재조회한 쪽이다. 그 사이 바뀐 값으로 토큰과 응답을 만들지
   *     않으려고 돌려준다. 어느 쪽이든 {@code authVersion}은 최초 Snapshot과 같음을 확인한 값이다
   */
  private User confirmSnapshotAndResetFailures(User snapshot, LocalDateTime now) {
    long userId = snapshot.getId();
    long authVersion = snapshot.getAuthVersion();
    if (resetFailures(userId, snapshot.getRowVersion(), authVersion, now)) {
      return snapshot;
    }

    User current =
        findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
    if (!current.getPasswordHash().equals(snapshot.getPasswordHash())
        || current.getAuthVersion() != authVersion) {
      throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
    }
    requireLoginAllowed(current, now);

    if (!resetFailures(userId, current.getRowVersion(), authVersion, now)) {
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
    return current;
  }

  /**
   * 조건부 UPDATE 한 번. commit까지 끝난 뒤에 결과를 판정한다.
   *
   * <p>예외 분류를 {@code execute} 밖에 두는 이유는 회원가입·이메일 인증과 같다 — 트랜잭션 안에서 잡으면 commit 실패를 성공으로 돌려준다.
   */
  private boolean resetFailures(
      long userId, long expectedRowVersion, long expectedAuthVersion, LocalDateTime now) {
    try {
      Integer updated =
          this.transactionOperations.execute(
              status ->
                  this.userRepository.recordLoginSuccess(
                      userId, expectedRowVersion, expectedAuthVersion, now));
      return updated != null && updated > 0;
    } catch (DataAccessException | TransactionException e) {
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
  }

  // ---------- 토큰 발급 ----------

  /**
   * Access·Refresh를 만들고 Redis에 family를 등록한다 (3.6.1·3.6.2절).
   *
   * <p><b>Snapshot의 {@code authVersion}으로 발급한다.</b> 재조회한 최신 값을 붙이면, 검증에 쓴 자격 증명과 토큰이 주장하는 세대가 달라져
   * 이미 무효화된 비밀번호로 최신 세션을 만들 수 있다.
   *
   * <p>{@code ALREADY_EXISTS}는 새 {@code familyId}·{@code jti}로 한 번만 다시 시도한다. 기존 Redis 값을 덮어쓰지 않으며 두
   * 번째 충돌도 성공으로 만들지 않는다. {@code UNAVAILABLE}은 적용 여부를 알 수 없으므로 재시도하지 않고 바로 끊는다 — 토큰을 돌려주지 않는 쪽이
   * 안전하다.
   */
  private AuthSession issueSession(User confirmed, long snapshotAuthVersion) {
    long userId = confirmed.getId();
    // 검증에 쓴 Snapshot의 값이다. 재조회한 사용자를 쓰더라도 그 authVersion이 같음을 이미 확인했다.
    long authVersion = snapshotAuthVersion;
    for (int attempt = 0; attempt < FAMILY_ISSUE_ATTEMPTS; attempt++) {
      String familyId = UUID.randomUUID().toString();
      IssuedAccessToken access =
          this.accessTokenIssuer.issue(
              new AccessTokenClaims(userId, confirmed.getRole(), authVersion));
      IssuedRefreshToken refresh =
          this.refreshTokenIssuer.issue(new RefreshTokenClaims(userId, familyId, authVersion));
      RefreshFamilyIssueResult result =
          this.refreshTokenStore.issueFamily(
              new RefreshTokenRecord(
                  userId,
                  familyId,
                  refresh.jti(),
                  // 해시는 발급기가 원문으로 만들어 실어 준다. application은 원문을 해시하지 않는다.
                  refresh.tokenHash(),
                  refresh.expiresAtEpochSecond()));
      switch (result) {
        case ISSUED -> {
          return new AuthSession(
              access.compactToken(),
              utc(access.expiresAtEpochSecond()),
              refresh.compactToken(),
              utc(refresh.expiresAtEpochSecond()),
              UserSummary.from(confirmed));
        }
        case UNAVAILABLE -> throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
        case ALREADY_EXISTS -> {
          // 새 식별자로 한 번 더 시도한다.
        }
      }
    }
    throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  private static LocalDateTime utc(long epochSecond) {
    return LocalDateTime.ofEpochSecond(epochSecond, 0, ZoneOffset.UTC);
  }
}
