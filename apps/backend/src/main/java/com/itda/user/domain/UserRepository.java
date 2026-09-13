package com.itda.user.domain;

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

  User save(User user);

  Optional<User> findById(Long id);

  Optional<User> findByUsername(String username);

  Optional<User> findByEmail(Email email);

  boolean existsByUsername(String username);

  boolean existsByEmail(Email email);
}
