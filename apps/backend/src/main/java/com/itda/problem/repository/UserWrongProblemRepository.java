package com.itda.problem.repository;

import com.itda.problem.domain.Level;
import com.itda.problem.domain.UserWrongProblem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserWrongProblemRepository extends JpaRepository<UserWrongProblem, Long> {

  List<UserWrongProblem> findAllByUser_IdAndLevel(Long userId, Level level);
}
