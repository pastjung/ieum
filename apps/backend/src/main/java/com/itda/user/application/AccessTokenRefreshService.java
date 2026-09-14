package com.itda.user.application;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.application.port.out.AccessTokenClaims;
import com.itda.user.application.port.out.AccessTokenIssuer;
import com.itda.user.application.port.out.IssuedAccessToken;
import com.itda.user.application.port.out.IssuedRefreshToken;
import com.itda.user.application.port.out.RefreshRotationResult;
import com.itda.user.application.port.out.RefreshTokenClaims;
import com.itda.user.application.port.out.RefreshTokenIssuer;
import com.itda.user.application.port.out.RefreshTokenLookupResult;
import com.itda.user.application.port.out.RefreshTokenRecord;
import com.itda.user.application.port.out.RefreshTokenStore;
import com.itda.user.application.port.out.RefreshTokenVerification;
import com.itda.user.application.port.out.RefreshTokenVerifier;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRepository;
import com.itda.user.domain.UserStatus;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Access Token 갱신과 Refresh 회전 (API 명세 3.3.1·3.6.1·3.6.2절).
 *
 * <p><b>플랫폼을 알지 않는다.</b> 쿠키에서 왔는지 body에서 왔는지는 presentation이 이미 정리했고, 결과도 플랫폼 중립인 {@link
 * AuthSession}이다.
 *
 * <p>검사 구간이 셋이고 <b>합치지 않는다</b> (3.6.2절). ① 초기 검증 — JWT 서명·claim, Redis 상태, MariaDB 사용자. ② 원자 회전 —
 * Redis Lua 한 번. ③ 회전 후 재확인 — MariaDB를 다시 읽는다. DB 조회가 두 번인 것은 낭비가 아니라 계약이다. 회전 사이에 탈퇴·비밀번호 재설정이
 * 끼어들면 이미 무효가 된 세대로 새 세션이 나가기 때문이다.
 *
 * <p>실패 원인을 밖으로 구분하지 않는다. 없음·만료·폐기·재사용·세대 불일치가 모두 {@code 401 INVALID_REFRESH_TOKEN} 하나다. 저장소를 읽거나
 * 바꾸지 못했거나 결과가 불확실한 경우만 {@code 503}으로 갈라진다.
 *
 * <p>Redis {@code lookup}은 <b>정확성의 권위가 아니다</b>. 값비싼 회전 경로로 가기 전 자원을 아끼는 사전 조회일 뿐이고, 재사용 탐지와 계열 폐기는
 * {@code rotateRefresh}가 한 번의 원자 연산에서 수행한다. 그래서 {@code ROTATED}·{@code REVOKED}·{@code
 * MISMATCHED}에서도 회전을 호출해 최종 판정을 받는다.
 */
public class AccessTokenRefreshService {

  /** 회전 시도 상한. {@code ALREADY_EXISTS}에만 쓰고 무제한 반복하지 않는다 (3.6.1절). */
  private static final int ROTATION_ATTEMPTS = 2;

  private final RefreshTokenVerifier refreshTokenVerifier;
  private final RefreshTokenStore refreshTokenStore;
  private final RefreshTokenIssuer refreshTokenIssuer;
  private final AccessTokenIssuer accessTokenIssuer;
  private final UserRepository userRepository;

  public AccessTokenRefreshService(
      RefreshTokenVerifier refreshTokenVerifier,
      RefreshTokenStore refreshTokenStore,
      RefreshTokenIssuer refreshTokenIssuer,
      AccessTokenIssuer accessTokenIssuer,
      UserRepository userRepository) {
    this.refreshTokenVerifier = refreshTokenVerifier;
    this.refreshTokenStore = refreshTokenStore;
    this.refreshTokenIssuer = refreshTokenIssuer;
    this.accessTokenIssuer = accessTokenIssuer;
    this.userRepository = userRepository;
  }

  /**
   * 제출된 Refresh Token을 회전하고 새 인증 세션을 만든다.
   *
   * @throws BusinessException 결정적 무효는 {@code INVALID_REFRESH_TOKEN}, 이메일 미인증은 {@code
   *     EMAIL_VERIFICATION_REQUIRED}, 저장소 장애·결과 불확실은 {@code AUTH_STATE_UNAVAILABLE}
   */
  public AuthSession refresh(AccessTokenRefreshCommand command) {
    RefreshTokenVerification.Verified verified = requireVerified(command.refreshToken());
    RefreshTokenRecord submitted = recordOf(verified);

    return switch (lookup(submitted)) {
      case ACTIVE -> rotateForSession(verified, submitted);
      // 재사용일 수 있다. 여기서 끝내면 탐지와 계열 폐기가 일어나는 원자 연산까지 가지 못한다.
      case ROTATED, REVOKED, MISMATCHED -> confirmReuse(verified, submitted);
      case NOT_FOUND, HASH_MISMATCH, FAMILY_REVOKED -> throw invalidRefreshToken();
      case UNAVAILABLE -> throw unavailable();
    };
  }

  // ---------- 초기 검증 ----------

  private RefreshTokenVerification.Verified requireVerified(String compactToken) {
    if (this.refreshTokenVerifier.verify(compactToken)
        instanceof RefreshTokenVerification.Verified verified) {
      return verified;
    }
    // 서명·용도·만료·claim 중 무엇이 틀렸는지 구분하지 않는다.
    throw invalidRefreshToken();
  }

  private RefreshTokenLookupResult lookup(RefreshTokenRecord submitted) {
    return this.refreshTokenStore.lookup(submitted);
  }

  /**
   * 회전 전 사용자 상태를 판정한다 (3.3.1절).
   *
   * <p>순서가 계약이다. {@code status != ACTIVE}를 먼저 보고, 다음이 {@code authVersion}, 두 조건을 통과한 ACTIVE 계정에만
   * {@code emailVerified}를 본다. 순서가 바뀌면 탈퇴 계정이 {@code 403}을 받아 계정 상태가 드러난다.
   */
  private User requireRefreshable(RefreshTokenVerification.Verified verified) {
    User user = findById(verified.userId()).orElse(null);
    if (user == null || user.getStatus() != UserStatus.ACTIVE) {
      throw invalidRefreshToken();
    }
    if (user.getAuthVersion() != verified.authVersion()) {
      throw invalidRefreshToken();
    }
    if (!user.isEmailVerified()) {
      throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
    }
    return user;
  }

  private Optional<User> findById(long userId) {
    try {
      return this.userRepository.findById(userId);
    } catch (DataAccessException | TransactionException e) {
      // 저장소를 읽지 못한 것을 자격 증명 실패로 위장하지 않는다 (3.6.2절). 예외 원문은 어디에도 남기지 않는다.
      throw unavailable();
    }
  }

  // ---------- 회전 ----------

  private AuthSession rotateForSession(
      RefreshTokenVerification.Verified verified, RefreshTokenRecord submitted) {
    User snapshot = requireRefreshable(verified);
    // 신규 토큰의 세대는 초기 Snapshot에서 확인한 값이다. 회전 도중 올라간 최신 값을 붙이면
    // 이미 무효가 된 자격으로 새 세대의 세션을 만들게 된다 (3.6.1절).
    long authVersion = snapshot.getAuthVersion();

    RotationAttempt attempt = rotate(verified, submitted, authVersion);
    switch (attempt.result()) {
      case ROTATED -> {
        // 회전이 확정됐다. 응답을 만들기 전에 DB를 한 번 더 본다.
      }
      case REUSED, NOT_FOUND, HASH_MISMATCH, FAMILY_REVOKED -> throw invalidRefreshToken();
      // 두 번째 충돌도 성공으로 만들지 않는다. 명령 실패·결과 불확실도 같다.
      case ALREADY_EXISTS, UNAVAILABLE -> throw unavailable();
    }

    User confirmed = requireStillRefreshable(verified, authVersion);
    IssuedRefreshToken next = attempt.issued();
    IssuedAccessToken access =
        this.accessTokenIssuer.issue(
            new AccessTokenClaims(confirmed.getId(), confirmed.getRole(), authVersion));
    return new AuthSession(
        access.compactToken(),
        utc(access.expiresAtEpochSecond()),
        next.compactToken(),
        utc(next.expiresAtEpochSecond()),
        UserSummary.from(confirmed));
  }

  /**
   * 재사용 여부를 원자 연산으로 확정한다.
   *
   * <p>여기서는 MariaDB를 읽지 않는다. 사전 조회가 이미 "정상 회전 후보가 아니다"라고 말했고, 남은 일은 계열을 폐기해야 하는 재사용인지 가리는 것뿐이라 사용자
   * 상태는 판정에 쓰이지 않는다.
   *
   * <p>{@code rotate}에 넘길 신규 토큰은 만들지만 <b>어떤 결과에서도 밖으로 나가지 않는다.</b>
   */
  private AuthSession confirmReuse(
      RefreshTokenVerification.Verified verified, RefreshTokenRecord submitted) {
    RotationAttempt attempt = rotate(verified, submitted, verified.authVersion());
    throw switch (attempt.result()) {
      case REUSED, NOT_FOUND, HASH_MISMATCH, FAMILY_REVOKED -> invalidRefreshToken();
      case ALREADY_EXISTS, UNAVAILABLE -> unavailable();
      // Redis 상태는 ACTIVE로 되돌아가지 않으므로 실제로는 도달하지 않는다. 그래도 회전이 성공한 채로
      // 끝내지 않는다 — 이 경로는 초기 DB 판정을 건너뛰었으므로 세션을 만들 근거가 없다. 계열을 닫고 끊는다.
      case ROTATED -> {
        revokeFamilyQuietly(verified);
        yield unavailable();
      }
    };
  }

  /**
   * 신규 {@code jti}로 회전을 시도한다. {@code ALREADY_EXISTS}면 새 식별자로 <b>한 번만</b> 다시 시도한다 (3.6.1절).
   *
   * <p>두 번째도 충돌하면 세 번째 발급도 Redis 호출도 하지 않는다. UUID v4가 두 번 연속 충돌했다면 재시도로 풀 문제가 아니다.
   */
  private RotationAttempt rotate(
      RefreshTokenVerification.Verified verified, RefreshTokenRecord submitted, long authVersion) {
    RotationAttempt last = null;
    for (int attempt = 0; attempt < ROTATION_ATTEMPTS; attempt++) {
      IssuedRefreshToken next =
          this.refreshTokenIssuer.issue(
              new RefreshTokenClaims(verified.userId(), verified.familyId(), authVersion));
      RefreshRotationResult result =
          this.refreshTokenStore.rotate(submitted, recordOf(verified.userId(), next));
      last = new RotationAttempt(result, next);
      if (result != RefreshRotationResult.ALREADY_EXISTS) {
        return last;
      }
    }
    return last;
  }

  // ---------- 회전 후 재확인 ----------

  /**
   * 응답 전에 {@code status}와 {@code auth_version}을 다시 본다. 선택이 아니라 필수다 (3.3.1절).
   *
   * <p>값이 달라졌으면 계열을 폐기하고 신규 토큰을 반환하지 않는다. 재확인 자체가 실패해도 마찬가지다 — 폐기를 시도하되 결과와 무관하게 토큰은 나가지 않는다.
   *
   * <p>{@code emailVerified}는 여기서 보지 않는다. 계약이 재확인 항목으로 정한 것은 두 값뿐이고, 그 사이 인증이 풀리는 전이는 탈퇴뿐이라 {@code
   * status} 검사에 이미 걸린다.
   */
  private User requireStillRefreshable(
      RefreshTokenVerification.Verified verified, long snapshotAuthVersion) {
    Optional<User> found;
    try {
      found = this.userRepository.findById(verified.userId());
    } catch (DataAccessException | TransactionException e) {
      revokeFamilyQuietly(verified);
      throw unavailable();
    }
    User current = found.orElse(null);
    if (current == null
        || current.getStatus() != UserStatus.ACTIVE
        || current.getAuthVersion() != snapshotAuthVersion) {
      revokeFamilyQuietly(verified);
      throw invalidRefreshToken();
    }
    return current;
  }

  /**
   * 계열 폐기를 시도하되 결과로 응답을 바꾸지 않는다.
   *
   * <p>폐기에 실패해도 신규 토큰을 반환하지 않는 것이 계약이다 (3.6.2절). 무효화의 최종 권위는 {@code users.auth_version}과 {@code
   * users.status}이고 Redis 정리는 사후 작업이므로, 여기서 실패를 다른 오류로 승격하지 않는다.
   */
  private void revokeFamilyQuietly(RefreshTokenVerification.Verified verified) {
    this.refreshTokenStore.revokeFamily(verified.userId(), verified.familyId());
  }

  // ---------- 값 변환 ----------

  private static RefreshTokenRecord recordOf(RefreshTokenVerification.Verified verified) {
    return new RefreshTokenRecord(
        verified.userId(),
        verified.familyId(),
        verified.jti(),
        // 해시는 검증기가 원문으로 만들어 실어 준다. application은 원문을 해시하지 않는다.
        verified.tokenHash(),
        verified.expiresAtEpochSecond());
  }

  private static RefreshTokenRecord recordOf(long userId, IssuedRefreshToken issued) {
    return new RefreshTokenRecord(
        userId, issued.familyId(), issued.jti(), issued.tokenHash(), issued.expiresAtEpochSecond());
  }

  private static LocalDateTime utc(long epochSecond) {
    return LocalDateTime.ofEpochSecond(epochSecond, 0, ZoneOffset.UTC);
  }

  private static BusinessException invalidRefreshToken() {
    return new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
  }

  private static BusinessException unavailable() {
    return new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  /** 회전 한 번의 결과와 그때 만든 신규 토큰. 성공이 아니면 {@code issued}는 버린다. */
  private record RotationAttempt(RefreshRotationResult result, IssuedRefreshToken issued) {

    @Override
    public String toString() {
      return "RotationAttempt[REDACTED]";
    }
  }
}
