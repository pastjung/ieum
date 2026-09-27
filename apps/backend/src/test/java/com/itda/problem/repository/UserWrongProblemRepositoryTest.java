package com.itda.problem.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.UserWrongProblem;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

/**
 * 학습하기의 "틀린 문제 4개"를 고르는 쿼리.
 *
 * <p>예전 쿼리({@code is_completed IS NULL})는 새 오답({@code false})을 한 건도 찾지 못했다. 엔티티 필드가 원시 boolean 이라
 * NULL 이 저장될 수 없기 때문이다. 실제 DB 로 확인한다.
 */
@DataJpaTest
class UserWrongProblemRepositoryTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 3, 0);

  @Autowired private TestEntityManager entityManager;
  @Autowired private UserWrongProblemRepository repository;

  private User learner() {
    return entityManager.persist(
        User.create("learner01", "argon2id$hash", UserRole.LEARNER, Email.of("a@b.com"), NOW));
  }

  private UserWrongProblem wrong(User user, String text) {
    Problem problem = entityManager.persist(new Problem(Level.WORD, text));
    return entityManager.persist(new UserWrongProblem(user, problem, Level.WORD));
  }

  @Test
  void 새로_틀린_문제는_재출제_후보로_찾는다() {
    User user = learner();
    wrong(user, "가방");
    wrong(user, "학교");
    entityManager.flush();
    entityManager.clear();

    List<UserWrongProblem> found =
        repository.findAllByUser_IdAndLevelAndIsCompletedFalse(user.getId(), Level.WORD);

    assertThat(found)
        .extracting(w -> w.getProblem().getTargetText())
        .containsExactlyInAnyOrder("가방", "학교");
  }

  @Test
  void 다시_맞혀_해소한_문제는_빠지고_또_틀리면_돌아온다() {
    User user = learner();
    UserWrongProblem solved = wrong(user, "가방");
    wrong(user, "학교");
    solved.complete();
    entityManager.flush();
    entityManager.clear();

    assertThat(repository.findAllByUser_IdAndLevelAndIsCompletedFalse(user.getId(), Level.WORD))
        .extracting(w -> w.getProblem().getTargetText())
        .containsExactly("학교");

    UserWrongProblem reloaded = entityManager.find(UserWrongProblem.class, solved.getId());
    reloaded.reopen();
    entityManager.flush();
    entityManager.clear();

    assertThat(repository.findAllByUser_IdAndLevelAndIsCompletedFalse(user.getId(), Level.WORD))
        .extracting(w -> w.getProblem().getTargetText())
        .containsExactlyInAnyOrder("가방", "학교");
  }
}
