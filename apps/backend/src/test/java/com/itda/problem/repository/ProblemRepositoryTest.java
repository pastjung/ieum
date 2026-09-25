package com.itda.problem.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

@DataJpaTest
class ProblemRepositoryTest {

  @Autowired private TestEntityManager entityManager;

  @Autowired private ProblemRepository problemRepository;

  @Test
  void 셀이_상한을_넘는_문제는_출제_후보에서_빠진다() {
    // FastAPI가 입력을 12칸까지만 받는다(router.py:121). 13칸 이상이 필요한 문제는
    // 정답을 넣어도 attempt-analysis가 거부하고, 그러면 그 세션을 완료할 수 없다.
    Problem shortOne = persistWithCells(Level.WORD, "가방", List.of(0b000001, 0b100000));
    Problem longOne =
        persistWithCells(Level.WORD, "긴문제", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13));

    List<Problem> assignable = problemRepository.findAssignableByLevel(Level.WORD, List.of(), 12L);

    assertThat(assignable).extracting(Problem::getId).contains(shortOne.getId());
    assertThat(assignable).extracting(Problem::getId).doesNotContain(longOne.getId());
  }

  @Test
  void 제외할_문제가_없어도_조회된다() {
    // 오답 문제가 하나도 없으면 빈 목록이 넘어온다. JPQL의 NOT IN은 빈 목록에서
    // 깨지기 쉬우므로 이 경우를 반드시 덮는다.
    Problem only = persistWithCells(Level.WORD, "가방", List.of(0b000001));

    List<Problem> assignable = problemRepository.findAssignableByLevel(Level.WORD, List.of(), 12L);

    assertThat(assignable).extracting(Problem::getId).contains(only.getId());
  }

  @Test
  void 제외_목록에_있는_문제는_빠진다() {
    Problem kept = persistWithCells(Level.WORD, "가방", List.of(0b000001));
    Problem excluded = persistWithCells(Level.WORD, "신발", List.of(0b000010));

    List<Problem> assignable =
        problemRepository.findAssignableByLevel(Level.WORD, List.of(excluded.getId()), 12L);

    assertThat(assignable).extracting(Problem::getId).contains(kept.getId());
    assertThat(assignable).extracting(Problem::getId).doesNotContain(excluded.getId());
  }

  private Problem persistWithCells(Level level, String targetText, List<Integer> expectedPatterns) {
    Problem saved = entityManager.persistFlushFind(new Problem(level, targetText));
    for (int index = 0; index < expectedPatterns.size(); index++) {
      entityManager.persist(new ProblemCell(saved, index + 1, expectedPatterns.get(index)));
    }
    entityManager.flush();
    return saved;
  }
}
