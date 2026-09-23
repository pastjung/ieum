package com.itda.learning.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LearningSessionRepository extends JpaRepository<LearningSession, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select session from LearningSession session "
          + "left join fetch session.curriculumStage where session.id = :sessionId")
  Optional<LearningSession> findByIdForCompletion(@Param("sessionId") Long sessionId);
}
