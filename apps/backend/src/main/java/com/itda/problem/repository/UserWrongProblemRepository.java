package com.itda.problem.repository;

import com.itda.problem.domain.Level;
import com.itda.problem.domain.UserWrongProblem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserWrongProblemRepository extends JpaRepository<UserWrongProblem, Long> {

  List<UserWrongProblem> findAllByUser_IdAndLevel(Long userId, Level level);

  List<UserWrongProblem> findAllByUser_IdAndProblem_IdIn(Long userId, List<Long> problemIds);

  /**
   * 아직 해소되지 않은 오답(다시 맞히지 못한 문제).
   *
   * <p>예전 조건은 {@code is_completed IS NULL} 이었다. 그런데 엔티티 필드가 원시 {@code boolean} 이라 새 오답은 항상 {@code
   * false} 로 저장되고, 해소되면 {@code true} 가 된다 — {@code NULL} 인 행이 생길 수 없어 오답 재출제가 한 번도 일어나지 않았다.
   */
  List<UserWrongProblem> findAllByUser_IdAndLevelAndIsCompletedFalse(Long userId, Level level);
}
