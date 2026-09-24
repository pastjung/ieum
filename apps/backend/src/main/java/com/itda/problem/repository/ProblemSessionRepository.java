package com.itda.problem.repository;

import com.itda.problem.domain.ProblemSession;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemSessionRepository extends JpaRepository<ProblemSession, Long> {

  @Query(
      "select problemSession from ProblemSession problemSession "
          + "join fetch problemSession.problem "
          + "where problemSession.session.id = :sessionId order by problemSession.id")
  List<ProblemSession> findAllForCompletion(@Param("sessionId") Long sessionId);

  List<ProblemSession> findAllBySession_IdOrderBySequence(Long sessionId);
}
