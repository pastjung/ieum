package com.itda.user.infrastructure;

import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 도메인 Repository 인터페이스의 JPA 어댑터 */
@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository {

  private final UserJpaRepository jpaRepository;

  @Override
  public User save(User user) {
    return jpaRepository.save(user);
  }

  @Override
  public Optional<User> findById(Long id) {
    return jpaRepository.findById(id);
  }

  @Override
  public Optional<User> findByUsername(String username) {
    return jpaRepository.findByUsername(username);
  }

  @Override
  public Optional<User> findByEmail(Email email) {
    return jpaRepository.findByEmail(email);
  }

  @Override
  public boolean existsByUsername(String username) {
    return jpaRepository.existsByUsername(username);
  }

  @Override
  public boolean existsByEmail(Email email) {
    return jpaRepository.existsByEmail(email);
  }

  @Override
  public int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
    return jpaRepository.markEmailVerified(userId, expectedRowVersion, now);
  }
}
