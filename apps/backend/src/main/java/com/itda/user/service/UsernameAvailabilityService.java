package com.itda.user.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.domain.Username;
import com.itda.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아이디를 가입에 쓸 수 있는지 조회한다 (API 명세 3.2.1절).
 *
 * <p>가입 화면이 로그인 전에 부르는 조회다. 인증을 요구하지 않는다.
 */
@Service
public class UsernameAvailabilityService {

  private final UserRepository userRepository;

  public UsernameAvailabilityService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * 정규화한 아이디와 사용 가능 여부를 함께 돌려준다.
   *
   * <p>형식 위반을 {@code available=false}로 답하지 않고 예외로 올린다. 쓸 수 없는 이유가 중복인지 형식인지 구분되지 않으면 클라이언트가 사용자에게
   * 무엇을 고치라고 안내할 수 없다.
   *
   * <p>결과는 <b>조회 시점의 판정일 뿐이다</b>. 이 조회와 실제 가입 사이에 다른 요청이 같은 값을 선점할 수 있으므로 {@code available=true}가
   * 이후 가입의 성공을 보장하지 않는다. 최종 판정은 가입의 UNIQUE 제약이며 그때 {@code 409}가 된다.
   *
   * @param rawUsername 요청 query parameter 원문. {@code null}이면 누락이며 형식 위반과 같은 오류다
   * @throws BusinessException 누락·형식·길이 위반은 {@code INVALID_ACCOUNT_INPUT}
   */
  @Transactional(readOnly = true)
  public Result check(String rawUsername) {
    Username username = normalize(rawUsername);
    return new Result(username.value(), !this.userRepository.existsByUsername(username.value()));
  }

  /**
   * 도메인 규칙으로 정규화한다.
   *
   * <p>{@link Username}이 던지는 {@link IllegalArgumentException}을 그대로 밖으로 보내지 않는다. 그 예외는 공통 {@code
   * 500} 경로로 흘러가는데, 이 API의 계약은 {@code 422 INVALID_ACCOUNT_INPUT}이다. 누락과 형식 위반을 구분하지 않는 이유도 같다 — 계약이
   * 둘에 같은 code를 준다.
   */
  private static Username normalize(String rawUsername) {
    try {
      return Username.of(rawUsername);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.INVALID_ACCOUNT_INPUT);
    }
  }

  /**
   * @param username 정규화된 아이디. 요청 원문과 다를 수 있다
   * @param available 조회 시점에 가입으로 쓸 수 있는지
   */
  public record Result(String username, boolean available) {}
}
