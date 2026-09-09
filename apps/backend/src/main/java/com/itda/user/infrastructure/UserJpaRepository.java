package com.itda.user.infrastructure;

import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserJpaRepository extends JpaRepository<User, Long> {

  Optional<User> findByUsername(String username);

  Optional<User> findByEmail(Email email);

  boolean existsByUsername(String username);

  boolean existsByEmail(Email email);
}
