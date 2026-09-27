package com.itda.user.service;

import com.itda.global.security.AccessTokenVerifier;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.domain.UserStatus;
import com.itda.user.repository.UserRepository;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Access Token을 검증하고 MariaDB의 현재 인증 상태까지 확인한다 (API 명세 3.1·3.6.1절).
 *
 * <p>서명과 claim만으로 통과시키지 않는 이유: 비밀번호 재설정·탈퇴는 {@code users.auth_version}과 {@code users.status}로 기존
 * Access를 무효화하며, 그 권위는 DB에 있다. Access 블랙리스트를 두지 않는 대신 보호 요청마다 DB를 확인한다.
 *
 * <p>업무 판정을 {@code global/security}가 아니라 여기에 두는 이유: 어떤 상태를 인증 성공으로 볼지는 계정 도메인의 규칙이다. 공통 인증 계층은 그
 * 결과를 HTTP로 옮기기만 한다.
 */
public class AccessAuthenticationService {

  /**
   * Access Token 기반 인증 판정의 닫힌 결과 (API 명세 3.5·3.6.2절).
   *
   * <p>{@code Rejected}와 {@code Unavailable}을 반드시 나눈다. 저장소가 정상 응답해 얻은 결정적 결과는 {@code 401
   * UNAUTHORIZED}이고, 상태를 읽지 못했거나 결과가 불확실한 경우는 {@code 503 AUTH_STATE_UNAVAILABLE}이다. 둘을 하나로 접으면 장애를
   * 자격 증명 실패로 위장하거나 그 반대가 된다.
   *
   * <p>{@code Rejected}는 사용자 없음·비활성·이메일 미인증·세대 불일치를 구분하지 않는다. 외부에서 구분되지 않아야 하는 값을 타입으로 남기지 않는다.
   */
  public sealed interface Result {

    /** 토큰과 저장소 인증 상태를 모두 통과했다. */
    record Authenticated(long userId, UserRole role) implements Result {

      /** 출력 문자열은 통합 전 top-level 타입 이름을 유지한다. */
      @Override
      public String toString() {
        return "AccessAuthenticationResult.Authenticated[REDACTED]";
      }
    }

    /** 결정적으로 인증할 수 없다. */
    record Rejected() implements Result {}

    /** 인증 상태를 확인하지 못했다. 인증 실패로 단정하지 않는다. */
    record Unavailable() implements Result {}
  }

  private final AccessTokenVerifier accessTokenVerifier;
  private final UserRepository userRepository;

  public AccessAuthenticationService(
      AccessTokenVerifier accessTokenVerifier, UserRepository userRepository) {
    this.accessTokenVerifier = accessTokenVerifier;
    this.userRepository = userRepository;
  }

  /**
   * @param compactToken Authorization 헤더에서 꺼낸 원문
   * @return 저장소 접근 실패·결과 불확실만 {@code Unavailable}이다. 그 밖의 예외는 삼키지 않고 전파해 기존 500 처리로 보낸다
   */
  public Result authenticate(String compactToken) {
    if (!(this.accessTokenVerifier.verify(compactToken)
        instanceof AccessTokenVerifier.Verification.Verified verified)) {
      return new Result.Rejected();
    }

    Optional<User> found;
    try {
      found = this.userRepository.findById(verified.userId());
    } catch (DataAccessException | TransactionException e) {
      // 저장소 접근 실패와 결과 불확실만 여기에 해당한다. 프로그래밍 오류까지 잡으면 버그가 503 뒤에 숨으므로
      // RuntimeException을 통째로 잡지 않는다.
      //
      // TransactionException을 함께 잡는 이유: 조회는 Spring Data의 읽기 트랜잭션 안에서 일어나는데,
      // EntityManager를 얻지 못하는 실패는 DataAccessException이 아니라 CannotCreateTransactionException으로
      // 나온다. 두 타입은 NestedRuntimeException의 형제라 한쪽만 잡으면 커넥션을 얻지 못한 장애가 그대로
      // 전파되어 500이 된다. 계약(API 명세 3.6.2절)은 저장소 상태를 읽지 못한 경우를 모두 fail-closed로
      // 다루라고 정한다.
      //
      // 예외 객체와 message는 어디에도 남기지 않는다. 저장소 종류·호스트·SQL이 외부로 새면 안 된다.
      return new Result.Unavailable();
    }

    return found
        .filter(user -> isAuthenticatable(user, verified.authVersion()))
        // 식별자와 역할은 서명·claim 검증을 통과한 토큰 값을 쓴다. 식별자는 조회 키였으므로 같은 값이고,
        // 역할은 DB 값과 비교하지 않는다 — 계약(API 명세 3.1절)이 정한 DB 재검증 항목은 사용자 존재,
        // status=ACTIVE, 이메일 인증 완료, authVersion 일치 네 가지뿐이다. 역할 변경 시 기존 토큰을
        // 어떻게 처리할지는 authVersion 증가 정책과 함께 role 변경 기능 이슈에서 정한다.
        .<Result>map(user -> new Result.Authenticated(verified.userId(), verified.role()))
        .orElseGet(Result.Rejected::new);
  }

  /** 네 조건은 외부에서 구분되지 않는다. 어느 것이 틀렸는지 반환값·로그로 남기지 않는다. */
  private static boolean isAuthenticatable(User user, long tokenAuthVersion) {
    return user.getStatus() == UserStatus.ACTIVE
        && user.isEmailVerified()
        && user.getAuthVersion() == tokenAuthVersion;
  }
}
