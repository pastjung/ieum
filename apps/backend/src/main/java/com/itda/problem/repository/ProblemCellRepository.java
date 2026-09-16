package com.itda.problem.repository;

import com.itda.problem.domain.ProblemCell;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemCellRepository extends JpaRepository<ProblemCell, Long> {

  /** 한 문제의 정답 Cell을 순번 오름차순으로 읽는다. */
  List<ProblemCell> findByProblemIdOrderByCellIndexAsc(Long problemId);
}
