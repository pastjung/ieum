package com.itda.learning.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AttemptCellRepository extends JpaRepository<AttemptCell, Long> {

  @Query(
      """
      select cell
      from AttemptCell cell
      where cell.attempt.id in :attemptIds
      order by cell.attempt.id, cell.cellIndex
      """)
  List<AttemptCell> findAllByAttemptIds(@Param("attemptIds") List<Long> attemptIds);
}
