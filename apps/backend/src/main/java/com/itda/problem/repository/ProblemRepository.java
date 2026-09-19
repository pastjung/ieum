package com.itda.problem.repository;

import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemRepository extends JpaRepository<Problem, Long> {
  Optional<Problem> findByTargetText(String s);

  List<Problem> findAllByLevel(Level level);
}
