package com.itda.learning.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemAttemptRepository extends JpaRepository<ProblemAttempt, Long> {

  List<ProblemAttempt> findAllByLearningSessionIdOrderBySessionItemNoAsc(Long learningSessionId);

  /** LIVE 풀이 한 건. (lecture_id, user_id, problem_id) UNIQUE이므로 많아야 한 건이다 (API 명세 8.1절). */
  Optional<ProblemAttempt> findByLectureIdAndUserIdAndProblemId(
      Long lectureId, Long userId, Long problemId);

  /** "내 실력" 화면의 푼 문제 수 */
  long countByUserId(Long userId);

  /** 정답률의 분자. 분모는 {@link #countByUserId} */
  long countByUserIdAndCorrectTrue(Long userId);

  /**
   * 커리큘럼 단계별 풀이·정답 수.
   *
   * <p>테마 선택 화면이 단계마다 "48문제 중 43개 맞힘"을 보여준다. 풀이 행을 전부 읽어 세면 한 사용자에게도 수백 행이 되므로 DB에서 집계한다.
   *
   * <p>{@code curriculum_stage_id}가 없는 풀이(복습 세션)는 빠진다 — 테마에 속하지 않는다.
   */
  @Query(
      """
    SELECT a.curriculumStageId AS curriculumStageId,
           COUNT(a) AS solvedCount,
           SUM(CASE WHEN a.correct = true THEN 1L ELSE 0L END) AS correctCount
    FROM ProblemAttempt a
    WHERE a.userId = :userId AND a.curriculumStageId IS NOT NULL
    GROUP BY a.curriculumStageId
    """)
  List<StageAttemptCount> countByStage(@Param("userId") Long userId);
}
