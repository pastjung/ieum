package com.itda.problem.service;

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
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(LinkedProblemReader.class)
class LinkedProblemReaderTest {

  @Autowired private TestEntityManager entityManager;
  @Autowired private LinkedProblemReader reader;

  @Test
  void 칸_순서대로_정답_점형을_담고_학습_요소로_표시_문구를_만든다() {
    LearningSkill onset =
        entityManager.persist(LearningSkill.of(SkillType.SKILL_01, "ㄱ", "첫소리 기본 자음", null));
    Problem problem = entityManager.persistFlushFind(new Problem(Level.CONSONANT, "ㄱ"));
    // 저장 순서와 칸 순서가 달라도 cell_index 순서여야 한다.
    entityManager.persist(new ProblemCell(problem, 2, 3));
    entityManager.persist(new ProblemCell(problem, 1, 8));
    entityManager.persist(new ProblemSkill(problem, onset));
    entityManager.flush();
    entityManager.clear();

    LinkedProblem linked = reader.read(entityManager.find(Problem.class, problem.getId()));

    assertThat(linked.problemId()).isEqualTo(problem.getId());
    assertThat(linked.targetText()).isEqualTo("ㄱ");
    assertThat(linked.displayText()).isEqualTo("초성 ㄱ");
    assertThat(linked.problemType()).isEqualTo(Level.CONSONANT);
    assertThat(linked.expectedPattern()).containsExactly(8, 3);
    assertThat(linked.cellCount()).isEqualTo(2);
  }

  @Test
  void 여러_문제를_받은_순서대로_돌려준다() {
    Problem word = entityManager.persistFlushFind(new Problem(Level.WORD, "그리고"));
    entityManager.persist(new ProblemCell(word, 1, 1));
    Problem vowel = entityManager.persistFlushFind(new Problem(Level.VOWEL, "ㅏ"));
    entityManager.persist(new ProblemCell(vowel, 1, 35));
    entityManager.flush();

    List<LinkedProblem> linked = reader.readAll(List.of(vowel, word));

    assertThat(linked).extracting(LinkedProblem::displayText).containsExactly("모음 ㅏ", "그리고");
    assertThat(linked)
        .extracting(LinkedProblem::expectedPattern)
        .containsExactly(List.of(35), List.of(1));
  }

  @Test
  void 빈_목록이면_빈_목록이다() {
    assertThat(reader.readAll(List.of())).isEmpty();
  }
}
