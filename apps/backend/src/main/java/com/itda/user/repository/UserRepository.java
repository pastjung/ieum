package com.itda.user.repository;

import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import java.time.LocalDateTime;
import java.util.Optional;

/** 도메인 계층의 Repository 인터페이스. JPA 등 기술 세부사항은 infrastructure 계층에서 구현한다 (의존성 역전). */
public interface UserRepository {

  /**
   * 이메일 인증을 조건부로 완료한다 (API 명세 3.6.2절, ERD 3.1절).
   *
   * <p>읽고-판단하고-쓰는 사이에 탈퇴나 다른 갱신이 끼어들 수 있으므로 판단 근거를 전부 UPDATE의 WHERE에 싣는다. 갱신된 행 수가 곧 경쟁 결과이며, 호출자는
   * 0행일 때 사용자를 다시 읽어 원인을 가른다.
   *
   * <p>{@code email_verified_at}이 이미 있는 행은 조건에서 제외해 <b>기존 인증 시각을 덮어쓰지 않는다</b>. 그 경우도 0행이 되고, 재조회에서
   * 인증 완료가 확인되면 멱등하게 성공이다.
   *
   * <p>{@code auth_version}은 건드리지 않는다. 이메일 인증은 기존 인증 세션을 무효화하는 처리가 아니다.
   *
   * @param expectedRowVersion 판단 근거로 삼은 Snapshot의 {@code row_version}
   * @param now UTC 기준 현재 시각. {@code email_verified_at}과 {@code updated_at}에 같은 값을 쓴다
   * @return 갱신된 행 수. 0 또는 1이다
   */
  int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now);

  /**
   * 로그인 실패를 원자적으로 누적한다 (ERD 3.1절).
   *
   * <p>읽고-더하고-저장하면 동시 실패가 서로의 증가를 덮어써 잠금을 우회할 수 있다. 그래서 한 문장에서 {@code failed_login_count}를 올리고, 그
   * 증가 결과가 한계에 닿았을 때만 {@code locked_until}을 기록한다.
   *
   * <p>이미 잠긴 행은 조건에서 제외한다. 잠금 중 재시도로 제한 종료 시각이 계속 밀리면 사실상 영구 잠금이 된다.
   *
   * @param lockedUntil 한계에 도달했을 때 기록할 제한 종료 시각
   * @return 갱신된 행 수. 0이면 계정이 ACTIVE가 아니거나 이미 잠긴 상태다
   */
  int recordLoginFailure(
      long userId, int maxAttempts, LocalDateTime lockedUntil, LocalDateTime now);

  /**
   * 로그인 성공 상태로 초기화하면서 Snapshot이 아직 유효한지 함께 확인한다 (ERD 3.1절, API 명세 3.6.2절).
   *
   * <p>{@code row_version}과 {@code auth_version}을 WHERE에 실어, Argon2id 검증에 쓴 Snapshot 이후 계정이 바뀌었다면
   * 0행이 되게 한다. 토큰 발급 직전의 마지막 관문이며 갱신된 행 수가 곧 경쟁 결과다.
   *
   * @return 갱신된 행 수. 0 또는 1이다
   */
  int recordLoginSuccess(
      long userId, long expectedRowVersion, long expectedAuthVersion, LocalDateTime now);

  User save(User user);

  Optional<User> findById(Long id);

  Optional<User> findByUsername(String username);

  Optional<User> findByEmail(Email email);

  boolean existsByUsername(String username);

  boolean existsByEmail(Email email);
}
