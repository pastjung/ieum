package com.itda.learning.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AttemptSkillResultRepository extends JpaRepository<AttemptSkillResult, Long> {}
