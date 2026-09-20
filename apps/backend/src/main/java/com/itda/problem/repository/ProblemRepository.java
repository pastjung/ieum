package com.itda.problem.repository;

import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemRepository extends JpaRepository<Problem, Long> {
  Optional<Problem> findByTargetText(String s);

  List<Problem> findAllByLevel(Level level);

  @Query(
      """
    SELECT DISTINCT p
    FROM Problem p
    JOIN ProblemSkill ps ON ps.problem = p
    WHERE p.level = :level
      AND ps.skill.id IN :learningSkillIds
    """)
  List<Problem> findAllByLevelAndLearningSkillIds(
      @Param("level") Level level, @Param("learningSkillIds") List<Long> learningSkillIds);
}
