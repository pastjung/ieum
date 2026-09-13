package com.itda.user.application;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRepository;
import com.itda.user.domain.UserStatus;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * 내 정보 조회 (API 명세 3.2·3.2.1절).
 *
 * <p><b>JWT claim을 응답 근거로 쓰지 않는다.</b> 토큰은 발급 시점의 사본이라 그 뒤 바뀐 {@code role}·{@code level}·인증 여부를 반영하지
 * 못한다. 인증이 준 것은 "누구인지"({@code userId})뿐이고 내용은 언제나 현재 DB에서 읽는다.
 *
 * <p>인증 필터를 통과한 뒤에도 사용자가 사라지거나 인증 가능한 상태가 아니게 될 수 있다. 그 경우 {@code 404}로 존재 여부를 알려 주지 않고 {@code 401
 * UNAUTHORIZED}로 일반화한다 — 인증 실패의 원인을 외부에서 구분할 수 없어야 한다는 계약(3.5절)과 같은 이유다.
 *
 * <p>읽기 실패와 결과 불확실은 {@code 401}로 접지 않고 {@code 503 AUTH_STATE_UNAVAILABLE}이다 (3.6.2절 fail-closed).
 * 장애를 인증 실패로 위장하면 클라이언트가 재로그인으로 잘못 복구한다.
 */
public class UserSummaryQueryService {

  private final UserRepository userRepository;

  public UserSummaryQueryService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * 인증된 사용자의 현재 요약을 읽는다.
   *
   * @param userId 인증 필터가 확인한 사용자 식별자. 요청 path·query·header에서 받지 않는다
   * @throws BusinessException 사용자가 없거나 인증 가능한 상태가 아니면 {@code UNAUTHORIZED}, 읽지 못하면 {@code
   *     AUTH_STATE_UNAVAILABLE}
   */
  public UserSummary getSummary(long userId) {
    Optional<User> found;
    try {
      found = this.userRepository.findById(userId);
    } catch (DataAccessException | TransactionException e) {
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
    return found
        .filter(UserSummaryQueryService::isAuthenticatable)
        .map(UserSummary::from)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
  }

  /**
   * 인증 필터가 본 것과 같은 조건이다 (ERD 3.1절).
   *
   * <p>필터 통과와 이 조회 사이에 탈퇴가 커밋될 수 있으므로 다시 확인한다. 여기서 통과시키면 이미 인증을 잃은 계정의 정보를 돌려주게 된다.
   */
  private static boolean isAuthenticatable(User user) {
    return user.getStatus() == UserStatus.ACTIVE && user.isEmailVerified();
  }
}
