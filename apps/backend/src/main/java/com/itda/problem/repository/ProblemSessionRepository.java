package com.itda.problem.repository;

import com.itda.problem.domain.ProblemSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemSessionRepository extends JpaRepository<ProblemSession, Long> {}
