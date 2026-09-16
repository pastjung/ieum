package com.itda.problem.repository;

import com.itda.problem.domain.ProblemSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemSkillRepository extends JpaRepository<ProblemSkill, Long> {
}
