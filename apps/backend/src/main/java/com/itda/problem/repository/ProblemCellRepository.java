package com.itda.problem.repository;

import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemCellRepository extends JpaRepository<ProblemCell, Long> {

  /** 한 문제의 정답 Cell을 순번 오름차순으로 읽는다. */
  List<ProblemCell> findByProblemIdOrderByCellIndexAsc(Long problemId);

  List<ProblemCell> findAllByProblem_IdInOrderByProblem_IdAscCellIndexAsc(List<Long> problemIds);

  List<ProblemCell> findAllByProblemOrderByCellIndex(Problem p);

  /** 문제 연결 전 셀 상한 검사에 쓴다 (API 명세 8.2.1절). */
  long countByProblem_Id(Long problemId);
}
