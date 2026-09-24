package com.itda.problem.repository;

import com.itda.problem.domain.ProblemSkill;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemSkillRepository extends JpaRepository<ProblemSkill, Long> {

  @Query(
      "select problemSkill from ProblemSkill problemSkill "
          + "join fetch problemSkill.skill "
          + "where problemSkill.problem.id in :problemIds")
  List<ProblemSkill> findAllWithSkillByProblemIdIn(@Param("problemIds") List<Long> problemIds);
}
