package com.itda.user.infrastructure;

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

  Optional<User> findByUsername(String username);

  Optional<User> findByEmail(Email email);

  boolean existsByUsername(String username);

  boolean existsByEmail(Email email);
}
