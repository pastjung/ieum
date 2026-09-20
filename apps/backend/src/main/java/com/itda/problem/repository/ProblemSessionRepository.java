package com.itda.problem.repository;

import com.itda.problem.domain.ProblemSession;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemSessionRepository extends JpaRepository<ProblemSession, Long> {
  List<ProblemSession> findAllBySession_IdOrderBySequence(Long sessionId);
}
