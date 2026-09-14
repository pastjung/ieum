package com.itda.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.user.repository.UserJpaRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

@DataJpaTest
class UserJpaMappingTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 12, 0);

  @Autowired private TestEntityManager entityManager;
  @Autowired private UserJpaRepository userJpaRepository;

  @Test
  void users_행을_저장하고_다시_읽는다() {
    User saved =
        entityManager.persistFlushFind(
            User.create("learner01", "argon2id$hash", UserRole.LEARNER, Email.of("A@B.com"), NOW));
    entityManager.clear();

    User found = userJpaRepository.findById(saved.getId()).orElseThrow();

    assertThat(found.getUsername()).isEqualTo("learner01");
    assertThat(found.getRole()).isEqualTo(UserRole.LEARNER);
    assertThat(found.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(found.getFailedLoginCount()).isZero();
    assertThat(found.getCreatedAt()).isEqualTo(NOW);
    assertThat(found.getLevel()).isNull();
    assertThat(found.getEmailVerifiedAt()).isNull();
    assertThat(found.getWithdrawnAt()).isNull();
    assertThat(found.getLockedUntil()).isNull();
  }

  @Test
  void 이메일_VO는_email_컬럼_하나로_매핑된다() {
    User saved =
        entityManager.persistFlushFind(
            User.create(
                "learner02", "hash", UserRole.LEARNER, Email.of("Learner02@Example.COM"), NOW));
    entityManager.clear();

    Object stored =
        entityManager
            .getEntityManager()
            .createNativeQuery("select email from users where id = :id")
            .setParameter("id", saved.getId())
            .getSingleResult();

    assertThat(stored).isEqualTo("learner02@example.com");
    assertThat(userJpaRepository.existsByEmail(Email.of("learner02@example.com"))).isTrue();
  }

  @Test
  void 아이디와_이메일로_조회한다() {
    entityManager.persistAndFlush(
        User.create("learner03", "hash", UserRole.INSTRUCTOR, Email.of("c@d.com"), NOW));
    entityManager.clear();

    assertThat(userJpaRepository.findByUsername("learner03")).isPresent();
    assertThat(userJpaRepository.findByEmail(Email.of("c@d.com"))).isPresent();
    assertThat(userJpaRepository.existsByUsername("nobody")).isFalse();
  }

  @Test
  void 탈퇴_상태_전이가_영속화된다() {
    User user =
        entityManager.persistFlushFind(
            User.create("learner04", "hash", UserRole.LEARNER, Email.of("e@f.com"), NOW));
    user.verifyEmail(NOW);
    user.withdraw(
        "withdrawn-4", Email.of("withdrawn-4@example.invalid"), "discarded", NOW.plusDays(1));
    entityManager.flush();
    entityManager.clear();

    User found = userJpaRepository.findById(user.getId()).orElseThrow();

    assertThat(found.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    assertThat(found.getWithdrawnAt()).isEqualTo(NOW.plusDays(1));
    assertThat(found.getEmailVerifiedAt()).isNull();
  }
}
