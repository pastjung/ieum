package com.itda.problem.repository;

import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemRepository extends JpaRepository<Problem, Long> {
  Optional<Problem> findByTargetText(String s);

  List<Problem> findAllByLevelAndIdNotIn(Level level, List<Long> ids);

  /**
   * 복습 출제용 후보. 학습요소 하나에 걸린 문제를 셀 수 상한 안에서 모두 가져온다.
   *
   * <p><b>사용자 레벨로 거르지 않는다.</b> 예전에는 {@code WHERE p.level = :level}이 있었는데, 그 레벨을 담는 {@code
   * users.level}을 설정하는 코드가 어디에도 없어({@code User.changeLevel} 호출부가 없다) 모든 계정이 {@code NULL}이었다.
   * SQL에서 {@code = NULL}은 참이 되지 않으므로 후보가 늘 0건이었고, 기록이 충분한 학습자도 복습을 시작할 수 없었다.
   *
   * <p>레벨을 채워 되살리는 대신 조건을 걷어낸다. 복습이 하는 일은 "내가 틀린 것을 다시 푸는 것"이고, 약한 요소의 문항이 마침 그 단계에 없으면
   * 복습 자체가 막힌다 — 그 요소를 틀렸다는 사실이 이미 그 문항을 풀 이유다.
   *
   * <p>셀 수 상한은 그대로 둔다. FastAPI가 {@code input_patterns}를 12칸으로 제한하므로 13칸 이상인 문제는 배정돼도 분석이 거부된다.
   */
  @Query(
      """
    SELECT DISTINCT p
    FROM Problem p
    JOIN ProblemSkill ps ON ps.problem = p
    WHERE ps.skill.id = :learningSkillId
      AND (SELECT COUNT(c) FROM ProblemCell c WHERE c.problem = p) <= :maxCells
    """)
  List<Problem> findAssignableByLearningSkillId(
      @Param("learningSkillId") Long learningSkillId, @Param("maxCells") long maxCells);

  /**
   * 출제 후보를 고른다.
   *
   * <p>{@code maxCells}로 셀 수를 제한하는 이유: AI가 입력을 12칸까지만 받는다 ({@code
   * apps/ai/app/presentation/api/backend/router.py} 의 {@code input_patterns}). 13칸 이상이 필요한 문제는 정답을
   * 넣어도 분석이 거부되고, 그러면 그 문항의 {@code ProblemSession}이 완료 처리되지 않아 세션 전체를 끝낼 수 없다.
   */
  @Query(
      """
      SELECT p
      FROM Problem p
      WHERE p.level = :level
        AND p.id NOT IN :excludedIds
        AND (SELECT COUNT(c) FROM ProblemCell c WHERE c.problem = p) <= :maxCells
      """)
  List<Problem> findAssignableByLevel(
      @Param("level") Level level,
      @Param("excludedIds") Collection<Long> excludedIds,
      @Param("maxCells") long maxCells);

  /**
   * 주어진 문제들 중 출제 가능한(셀 수가 상한 이하인) 것의 id만 고른다.
   *
   * <p>오답 재출제 경로가 쓴다. 오답 문제는 {@link #findAssignableByLevel}의 후보에서 <b>제외</b>될 뿐 따로 배정되므로, 그 조회의 셀
   * 상한을 거치지 않는다. 13칸 이상인 문제를 틀린 적 있는 학습자는 그것을 다시 배정받고, FastAPI가 분석을 거부해 그 문항의 {@code
   * ProblemSession}이 완료되지 않아 세션 전체를 끝낼 수 없다. 상한은 두 경로에서 같은 값({@code MAX_ASSIGNABLE_CELLS})이어야 한다.
   *
   * <p>{@code ids}가 비어 있으면 호출하지 않는다 — 빈 {@code IN} 목록은 JPQL에서 다루지 않는 편이 안전하다.
   */
  @Query(
      """
      SELECT p.id
      FROM Problem p
      WHERE p.id IN :ids
        AND (SELECT COUNT(c) FROM ProblemCell c WHERE c.problem = p) <= :maxCells
      """)
  List<Long> findAssignableIdsIn(
      @Param("ids") Collection<Long> ids, @Param("maxCells") long maxCells);
}
