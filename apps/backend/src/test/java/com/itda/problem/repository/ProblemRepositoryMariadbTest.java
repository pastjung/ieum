package com.itda.problem.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

/**
 * 출제 후보 조회를 운영과 같은 MariaDB 11.8.9에 대고 검증한다.
 *
 * <p>H2로는 증명되지 않는 것만 여기서 본다 — <b>빈 컬렉션을 {@code NOT IN}에 넘겼을 때</b>다. {@code
 * ProblemRepository#findAssignableByLevel}의 {@code excludedIds}는 오답 기록이 없는 신규 학습자에게 항상 빈 목록이므로, 이
 * 경우가 깨지면 <b>첫 학습 세션 생성이 전부 실패한다</b>. SQL의 {@code NOT IN ()}은 표준 문법이 아니고 {@code NOT IN (NULL)}은 어떤
 * 행과도 일치하지 않으므로, Hibernate가 무엇으로 번역하느냐에 결과가 달려 있다 — 방언마다 다를 수 있어 실제 DB로만 확인된다.
 *
 * <p>{@code IN}을 쓰는 {@code findAssignableIdsIn}은 빈 목록으로 호출하지 않는다({@code
 * ProblemService.assignableOnly}가 먼저 막는다). 여기서도 그 계약대로 비어 있지 않은 목록만 본다.
 */
@Tag("mariadb")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProblemRepositoryMariadbTest {

  @Autowired private TestEntityManager entityManager;

  @Autowired private ProblemRepository problemRepository;

  @Test
  void 제외할_문제가_없어도_출제_후보가_조회된다() {
    Problem only = persistWithCells(Level.WORD, "가방", List.of(0b000001));

    List<Problem> assignable = problemRepository.findAssignableByLevel(Level.WORD, List.of(), 12L);

    assertThat(assignable).extracting(Problem::getId).contains(only.getId());
  }

  @Test
  void 제외_목록이_비어_있어도_셀_상한은_그대로_적용된다() {
    // 빈 NOT IN 이 조건 전체를 무력화하면(예: WHERE 절이 통째로 참이 되면) 13칸 문제까지 섞인다
    Problem shortOne = persistWithCells(Level.WORD, "짧은", List.of(0b000001));
    Problem longOne =
        persistWithCells(Level.WORD, "긴문제", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13));

    List<Problem> assignable = problemRepository.findAssignableByLevel(Level.WORD, List.of(), 12L);

    assertThat(assignable).extracting(Problem::getId).contains(shortOne.getId());
    assertThat(assignable).extracting(Problem::getId).doesNotContain(longOne.getId());
  }

  @Test
  void 제외_목록에_있는_문제는_빠진다() {
    Problem kept = persistWithCells(Level.WORD, "남길", List.of(0b000001));
    Problem excluded = persistWithCells(Level.WORD, "뺄", List.of(0b000010));

    List<Problem> assignable =
        problemRepository.findAssignableByLevel(Level.WORD, List.of(excluded.getId()), 12L);

    assertThat(assignable).extracting(Problem::getId).contains(kept.getId());
    assertThat(assignable).extracting(Problem::getId).doesNotContain(excluded.getId());
  }

  @Test
  void 오답_후보도_셀_상한으로_걸러진다() {
    Problem shortOne = persistWithCells(Level.WORD, "짧은오답", List.of(0b000001));
    Problem longOne =
        persistWithCells(Level.WORD, "긴오답", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13));

    List<Long> assignable =
        problemRepository.findAssignableIdsIn(List.of(shortOne.getId(), longOne.getId()), 12L);

    assertThat(assignable).containsExactly(shortOne.getId());
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
