package com.itda.learning.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemAttemptRepository extends JpaRepository<ProblemAttempt, Long> {

  List<ProblemAttempt> findAllByLearningSessionIdOrderBySessionItemNoAsc(Long learningSessionId);

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

  /**
   * 문제 단계(Level)별 풀이·정답 수.
   *
   * <p>"내 실력" 화면이 단계마다 "48문제 중 43개 맞힘"을 보여준다.
   *
   * <p><b>묶는 축이 {@link #countByStage}와 다르다.</b> 저쪽은 세션이 어느 단계였나({@code curriculum_stage_id})로 묶어 복습
   * 세션 풀이가 통째로 빠진다 — 복습 세션은 그 값이 NULL이기 때문이다({@code ProblemService.createSession(userId, null)}).
   * 이쪽은 문제 자신의 단계({@code problems.level})로 묶으므로 복습 풀이도 제자리에 들어간다.
   *
   * <p>{@code ProblemAttempt}는 문제를 연관관계가 아니라 {@code problemId} 컬럼으로 들고 있어 엔티티 조인을 쓴다.
   *
   * <p>실시간 수업(LIVE) 풀이를 걸러내지 않는다. 같은 화면의 전체 "푼 문제"·"정답률"을 내는 {@link #countByUserId}가 걸러내지 않으므로, 여기만
   * 좁히면 한 화면 안에서 전체 합과 단계 합이 갈린다.
   */
  @Query(
      """
    SELECT p.level AS level,
           COUNT(a) AS solvedCount,
           SUM(CASE WHEN a.correct = true THEN 1L ELSE 0L END) AS correctCount
    FROM ProblemAttempt a
      JOIN Problem p ON p.id = a.problemId
    WHERE a.userId = :userId
    GROUP BY p.level
    """)
  List<LevelAttemptCount> countByLevel(@Param("userId") Long userId);
}
