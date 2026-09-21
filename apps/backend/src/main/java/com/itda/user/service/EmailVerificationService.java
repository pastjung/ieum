package com.itda.user.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.User;
import com.itda.user.domain.UserStatus;
import com.itda.user.repository.ActionTokenStore;
import com.itda.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 이메일 인증 완료 (API 명세 3.4.1·3.6.2절).
 *
 * <p><b>순서가 계약이다.</b> 형식 → JWT → 용도 → 사용자 상태를 전부 끝낸 다음에야 Redis에서 토큰을 소비하고, 마지막에 MariaDB를 바꾼다.
 *
 * <p>MariaDB를 먼저 바꾸고 소비를 나중에 하면 소비 실패 시 같은 토큰을 한 번 더 쓸 수 있다. 반대로 Redis를 먼저 소비하면 이후 DB가 실패해도 토큰만
 * 소진되고 사용자는 새 메일을 요청해 복구한다. 재사용 가능한 토큰을 남기는 것보다 fail-closed로 소진하는 쪽이 안전하다는 것이 계약의 판단이다.
 *
 * <p>두 저장소를 한 트랜잭션으로 묶지 않는다. {@code PROCESSING}·{@code RESERVED} 중간 상태, DB 토큰 테이블, 완료 처리용 outbox를
 * 도입하지 않으며 소비한 토큰을 되돌리는 보상도 하지 않는다 (3.6.2절).
 *
 * <p>실패 사유를 외부에서 구분하지 않는다. 위조·만료·소비 완료·무효화·용도 불일치·사용자 없음·비ACTIVE가 모두 {@code 400
 * ACTION_TOKEN_INVALID} 하나다. 계정 상태를 드러내는 별도 code를 쓰지 않는다.
 */
public class EmailVerificationService {

  private final EmailVerificationTokenVerifier tokenVerifier;
  private final ActionTokenStore actionTokenStore;
  private final UserRepository userRepository;
  private final TransactionOperations transactionOperations;
  private final Clock clock;

  public EmailVerificationService(
      EmailVerificationTokenVerifier tokenVerifier,
      ActionTokenStore actionTokenStore,
      UserRepository userRepository,
      TransactionOperations transactionOperations,
      Clock clock) {
    this.tokenVerifier = tokenVerifier;
    this.actionTokenStore = actionTokenStore;
    this.userRepository = userRepository;
    this.transactionOperations = transactionOperations;
    this.clock = clock;
  }

  /**
   * 토큰을 소비하고 이메일 인증을 완료한다.
   *
   * @param rawToken 요청 body의 {@code token} 원문. opaque 값이므로 trim·정규화·대소문자 변환을 하지 않는다
   * @throws BusinessException 형식 위반은 {@code INVALID_ACCOUNT_INPUT}, 토큰 부적격은 {@code
   *     ACTION_TOKEN_INVALID}, 저장소 장애·결과 불확실은 {@code AUTH_STATE_UNAVAILABLE}
   */
  public void complete(String rawToken) {
    requirePresent(rawToken);
    ActionTokenVerification.Verified verified = verify(rawToken);
    User snapshot = findActiveUser(verified.userId());

    consume(verified);

    markVerified(verified.userId(), snapshot.getRowVersion());
  }

  /**
   * 1단계. 여기만 {@code 422}다 — 요청이 계약 형식을 갖추지 못한 경우이며 토큰·계정 상태와 무관하다 (3.4.1절).
   *
   * <p>{@code isBlank()}가 빈 문자열과 공백만 있는 값을 함께 거른다. 그 밖의 문자열은 <b>trim하지 않고</b> 원문 그대로 검증기에 넘긴다.
   */
  private static void requirePresent(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      throw new BusinessException(ErrorCode.INVALID_ACCOUNT_INPUT);
    }
  }

  /**
   * 2·3단계. 서명·{@code alg}·{@code kid}·{@code typ}·{@code iss}·{@code aud}·{@code exp}와 용도를 port가
   * 고정한다.
   */
  private ActionTokenVerification.Verified verify(String rawToken) {
    if (!(this.tokenVerifier.verify(rawToken) instanceof ActionTokenVerification.Verified verified)
        || verified.purpose() != AccountActionPurpose.EMAIL_VERIFICATION) {
      throw new BusinessException(ErrorCode.ACTION_TOKEN_INVALID);
    }
    return verified;
  }

  /**
   * 4단계. 소비 전에 사용자 상태를 확인한다.
   *
   * <p>여기서 읽은 {@code row_version}이 뒤따르는 조건부 갱신의 Snapshot이 된다. 탈퇴는 {@code email_verified_at}을 NULL로
   * 되돌리므로 비ACTIVE 계정의 재인증을 허용하지 않는다.
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

  /**
   * 5단계. 적격 조건 확인과 {@code CONSUMED} 전이가 한 원자 연산에서 일어난다.
   *
   * <p>사전 {@code lookup}을 쓰지 않는다. 조회와 소비 사이에 상태가 바뀔 수 있어 정확성의 근거가 되지 못하고, 이 API에는 사전 조회로 아낄 비싼 계산도
   * 없다.
   *
   * <p>결과를 알 수 없으면 MariaDB를 <b>시작하지 않는다</b>. 실제로 소비됐을 수도, 아닐 수도 있으므로 어느 쪽인지 추측해 진행하지 않는다 (3.6.2절).
   */
  private void consume(ActionTokenVerification.Verified verified) {
    ActionTokenStore.ConsumeResult result =
        this.actionTokenStore.consume(
            new ActionTokenStore.TokenRecord(
                verified.userId(),
                verified.purpose(),
                verified.jti(),
                verified.tokenHash(),
                verified.expiresAtEpochSecond()));
    switch (result) {
      case CONSUMED -> {
        // 소비 확정. 여기서부터 실패해도 토큰을 되돌리지 않는다.
      }
      case UNAVAILABLE -> throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
      // NOT_FOUND·NOT_CURRENT·HASH_MISMATCH·ALREADY_CONSUMED·INVALIDATED는 전부 결정적 부적격이다.
      default -> throw new BusinessException(ErrorCode.ACTION_TOKEN_INVALID);
    }
  }

  /**
   * 6단계. 조건부 갱신 한 번, 어긋나면 재조회 후 최대 <b>한 번</b>만 더 시도한다 (3.6.2절).
   *
   * <p>토큰은 이미 소비됐고 실패해도 복원되지 않는다. 그래서 무제한 재시도로 DB 경합을 끝까지 밀어붙이지 않고, 두 번째도 어긋나면 결과를 단정하지 않은 채 {@code
   * 503}으로 끊는다.
   */
  private void markVerified(long userId, long snapshotRowVersion) {
    if (update(userId, snapshotRowVersion)) {
      return;
    }

    User current =
        findUser(userId).orElseThrow(() -> new BusinessException(ErrorCode.ACTION_TOKEN_INVALID));
    if (current.getStatus() != UserStatus.ACTIVE) {
      throw new BusinessException(ErrorCode.ACTION_TOKEN_INVALID);
    }
    if (current.isEmailVerified()) {
      // 목적 상태가 이미 달성됐다. 기존 인증 시각을 덮어쓰지 않고 멱등하게 성공이다 (3.4.1절).
      return;
    }
    if (!update(userId, current.getRowVersion())) {
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
  }

  /**
   * 조건부 갱신 한 번. commit까지 끝나고 나서 결과를 판정한다.
   *
   * <p>예외 분류를 {@code execute} 밖에 두는 이유는 회원가입과 같다 — 트랜잭션 안에서 잡으면 commit 실패를 성공으로 돌려주게 된다.
   */
  private boolean update(long userId, long expectedRowVersion) {
    LocalDateTime now = LocalDateTime.now(this.clock);
    try {
      Integer updated =
          this.transactionOperations.execute(
              status -> this.userRepository.markEmailVerified(userId, expectedRowVersion, now));
      return updated != null && updated > 0;
    } catch (DataAccessException | TransactionException e) {
      // 소비는 확정됐지만 DB가 실패했다. 204로 만들지 않고, 토큰도 복원하지 않는다 (3.6.2절).
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
  }
}
