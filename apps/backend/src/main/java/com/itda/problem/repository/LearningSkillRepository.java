package com.itda.problem.repository;

import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.SkillType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LearningSkillRepository extends JpaRepository<LearningSkill, Long> {
  Optional<LearningSkill> findBySkillTypeAndMetadata(SkillType skillType, String metadata);
}
