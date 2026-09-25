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

  @Query(
      """
    SELECT DISTINCT p
    FROM Problem p
    JOIN ProblemSkill ps ON ps.problem = p
    WHERE p.level = :level
      AND ps.skill.id = :learningSkillId
    """)
  List<Problem> findAllByLevelAndLearningSkillId(
      @Param("level") Level level, @Param("learningSkillId") Long learningSkillId);

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
}
