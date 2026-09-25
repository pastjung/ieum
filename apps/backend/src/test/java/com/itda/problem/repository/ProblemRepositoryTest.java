package com.itda.problem.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.ProblemSkill;
import com.itda.problem.domain.SkillType;
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

  /**
   * 경계값 회귀 테스트. 이 테스트가 없으면 쿼리의 {@code <=}가 실수로 {@code <}가 되어도(12칸 문제까지 버려도) 다른 테스트는 통과한다 — "12는 포함,
   * 13은 제외"가 이 기능의 핵심이므로 그 경계를 직접 고정한다.
   */
  @Test
  void 정확히_상한인_문제는_포함된다() {
    Problem exact =
        persistWithCells(Level.WORD, "딱상한", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));

    List<Problem> assignable = problemRepository.findAssignableByLevel(Level.WORD, List.of(), 12L);

    assertThat(assignable).extracting(Problem::getId).contains(exact.getId());
  }

  /**
   * 복습 세션({@code reviewWithNewSession})이 쓰는 조회다. {@link #findAssignableByLevel}과 본질이 같은 "레벨로 후보를
   * 뽑는" 조회이므로 같은 셀 상한이 적용되어야 한다 — 그렇지 않으면 복습 경로로 13칸 이상 문제가 그대로 배정된다.
   */
  @Test
  void 요소로_찾을_때도_셀이_상한을_넘는_문제는_빠진다() {
    Problem shortOne = persistWithCells(Level.WORD, "가방", List.of(0b000001));
    Problem longOne =
        persistWithCells(Level.WORD, "긴문제", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13));
    LearningSkill skill =
        entityManager.persistFlushFind(LearningSkill.of(SkillType.SKILL_01, "ㄱ", "초성 ㄱ", null));
    entityManager.persist(new ProblemSkill(shortOne, skill));
    entityManager.persist(new ProblemSkill(longOne, skill));
    entityManager.flush();

    List<Problem> found =
        problemRepository.findAllByLevelAndLearningSkillId(Level.WORD, skill.getId(), 12L);

    assertThat(found).extracting(Problem::getId).contains(shortOne.getId());
    assertThat(found).extracting(Problem::getId).doesNotContain(longOne.getId());
  }

  /**
   * 오답 재출제 경로가 쓰는 조회다. 오답 문제는 {@link ProblemRepository#findAssignableByLevel}의 후보에서 제외될 뿐 따로 배정되므로,
   * 여기서 같은 셀 상한을 적용하지 않으면 13칸 이상 문제가 상한 검사를 통째로 비껴간다.
   */
  @Test
  void 지정한_문제들_중_셀이_상한_이하인_것만_돌려준다() {
    Problem shortOne = persistWithCells(Level.WORD, "가방", List.of(0b000001));
    Problem exact =
        persistWithCells(Level.WORD, "딱상한", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
    Problem longOne =
        persistWithCells(Level.WORD, "긴문제", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13));

    List<Long> assignable =
        problemRepository.findAssignableIdsIn(
            List.of(shortOne.getId(), exact.getId(), longOne.getId()), 12L);

    assertThat(assignable).contains(shortOne.getId(), exact.getId());
    assertThat(assignable).doesNotContain(longOne.getId());
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
