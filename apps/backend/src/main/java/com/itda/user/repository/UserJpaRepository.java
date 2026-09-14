package com.itda.user.repository;

import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserJpaRepository extends JpaRepository<User, Long> {

  /**
   * 이메일 인증 완료의 조건부 UPDATE (API 명세 3.6.2절).
   *
   * <p>{@code update versioned}를 쓴다. Hibernate가 {@code row_version}을 직접 증가시키므로 애플리케이션이 계산한 값을 넣지
   * 않고, 그 결과 이 문장은 낙관적 잠금 규칙과 같은 방식으로 행을 옮긴다.
   *
   * <p>조건을 셋 다 WHERE에 싣는다. {@code status}는 탈퇴와의 경쟁을, {@code row_version}은 Snapshot 이후의 변경을, {@code
   * email_verified_at is null}은 기존 인증 시각 덮어쓰기를 막는다.
   *
   * <p>{@code clearAutomatically}로 영속성 컨텍스트를 비운다. bulk UPDATE는 컨텍스트를 우회하므로 stale 엔티티가 남으면 이후 조회가 옛
   * 값을 돌려준다 (ERD 3.1절).
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update versioned User u
         set u.emailVerifiedAt = :now,
             u.updatedAt = :now
       where u.id = :userId
         and u.status = com.itda.user.domain.UserStatus.ACTIVE
         and u.rowVersion = :expectedRowVersion
         and u.emailVerifiedAt is null
      """)
  int markEmailVerified(
      @Param("userId") long userId,
      @Param("expectedRowVersion") long expectedRowVersion,
      @Param("now") LocalDateTime now);

  /**
   * 로그인 실패 누적의 조건부 UPDATE (ERD 3.1절).
   *
   * <p>{@code locked_until}을 {@code case} 식으로 같은 문장에서 정한다. 증가 후 값이 한계에 닿는 요청만 제한 시각을 쓰고 나머지는 기존 값을
   * 유지하므로, 카운터 증가와 잠금 판정 사이에 다른 요청이 끼어들 틈이 없다.
   *
   * <p><b>{@code locked_until} 대입이 먼저다.</b> MariaDB는 한 UPDATE의 SET을 왼쪽부터 평가하면서 <b>이미 갱신된 값</b>을 뒤
   * 항에서 쓴다. 증가를 먼저 쓰면 {@code case}가 새 값을 보고 한 번 이른 시도에서 잠가 버린다. 아직 아무것도 바꾸지 않은 첫 항에서 판정해야 평가 순서와
   * 무관하게 "증가 후 값이 한계에 닿는 시도"에서만 잠긴다.
   *
   * <p>{@code locked_until is null or locked_until <= :now} 조건이 "이미 잠긴 행은 건드리지 않는다"를 만든다.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update versioned User u
         set u.lockedUntil =
                 case when u.failedLoginCount + 1 >= :maxAttempts then :lockedUntil
                      else u.lockedUntil end,
             u.failedLoginCount = u.failedLoginCount + 1,
             u.updatedAt = :now
       where u.id = :userId
         and u.status = com.itda.user.domain.UserStatus.ACTIVE
         and (u.lockedUntil is null or u.lockedUntil <= :now)
      """)
  int recordLoginFailure(
      @Param("userId") long userId,
      @Param("maxAttempts") int maxAttempts,
      @Param("lockedUntil") LocalDateTime lockedUntil,
      @Param("now") LocalDateTime now);

  /**
   * 로그인 성공 초기화의 조건부 UPDATE (ERD 3.1절, API 명세 3.6.2절).
   *
   * <p>{@code auth_version}은 SET에 넣지 않는다. 로그인은 기존 세션을 무효화하는 처리가 아니며, WHERE의 비교 대상으로만 쓴다.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update versioned User u
         set u.failedLoginCount = 0,
             u.lockedUntil = null,
             u.updatedAt = :now
       where u.id = :userId
         and u.status = com.itda.user.domain.UserStatus.ACTIVE
         and u.rowVersion = :expectedRowVersion
         and u.authVersion = :expectedAuthVersion
      """)
  int recordLoginSuccess(
      @Param("userId") long userId,
      @Param("expectedRowVersion") long expectedRowVersion,
      @Param("expectedAuthVersion") long expectedAuthVersion,
      @Param("now") LocalDateTime now);

  Optional<User> findByUsername(String username);

  Optional<User> findByEmail(Email email);

  boolean existsByUsername(String username);

  boolean existsByEmail(Email email);
}
