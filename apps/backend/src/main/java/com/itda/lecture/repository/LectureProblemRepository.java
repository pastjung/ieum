package com.itda.lecture.repository;

import com.itda.lecture.domain.LectureProblem;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LectureProblemRepository extends JpaRepository<LectureProblem, Long> {

  /** (lecture, problem) UNIQUE이므로 많아야 한 건이다. */
  Optional<LectureProblem> findByLecture_IdAndProblem_Id(Long lectureId, Long problemId);
}
