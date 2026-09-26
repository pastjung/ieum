package com.itda.curriculum.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CurriculumStageRepository extends JpaRepository<CurriculumStage, Long> {
  Optional<CurriculumStage> findById(Long curriculumId);
}
