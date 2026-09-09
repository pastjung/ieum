package com.itda.user.domain;

import java.util.Optional;

/** 도메인 계층의 Repository 인터페이스. JPA 등 기술 세부사항은 infrastructure 계층에서 구현한다 (의존성 역전). */
public interface UserRepository {

  User save(User user);

  Optional<User> findById(Long id);

  Optional<User> findByUsername(String username);

  Optional<User> findByEmail(Email email);

  boolean existsByUsername(String username);

  boolean existsByEmail(Email email);
}
