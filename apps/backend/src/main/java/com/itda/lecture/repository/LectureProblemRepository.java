package com.itda.lecture.repository;

import com.itda.lecture.domain.LectureProblem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LectureProblemRepository extends JpaRepository<LectureProblem, Long> {

  /** (lecture, problem) UNIQUE이므로 많아야 한 건이다. */
  Optional<LectureProblem> findByLecture_IdAndProblem_Id(Long lectureId, Long problemId);

  /** 강사 Snapshot의 linkedProblems. 연결 순서(id 오름차순)대로 문제를 함께 읽는다 (API 명세 8.2절). */
  @Query(
      "select lp from LectureProblem lp join fetch lp.problem "
          + "where lp.lecture.id = :lectureId order by lp.id")
  List<LectureProblem> findAllWithProblemByLectureId(@Param("lectureId") Long lectureId);
}
