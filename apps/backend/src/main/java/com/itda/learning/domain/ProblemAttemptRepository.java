package com.itda.learning.domain;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemAttemptRepository extends JpaRepository<ProblemAttempt, Long> {

  @EntityGraph(attributePaths = "cells")
  List<ProblemAttempt> findAllByLearningSessionIdOrderBySessionItemNoAsc(Long learningSessionId);
}
