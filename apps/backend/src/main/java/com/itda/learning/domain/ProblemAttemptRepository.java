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

  /** 강사 답안 목록. 제출 시각 오름차순, 같은 시각이면 id 오름차순 (API 명세 8.2.2절). */
  List<ProblemAttempt> findAllByLectureIdAndProblemIdOrderByAttemptedAtAscIdAsc(
      Long lectureId, Long problemId);

  /** "내 실력" 화면의 푼 문제 수. LIVE 풀이는 뺀다 — 수업 중 풀이는 개인 정답률 통계에 넣지 않는다(API 명세 8.1절). */
  long countByUserIdAndAttemptMode(Long userId, AttemptMode attemptMode);

  /** 정답률의 분자. 분모는 {@link #countByUserIdAndAttemptMode} */
  long countByUserIdAndAttemptModeAndCorrectTrue(Long userId, AttemptMode attemptMode);

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
   * <p>실시간 수업(LIVE) 풀이는 세지 않는다(API 명세 8.1절). 같은 화면의 전체 "푼 문제"·"정답률"을 내는 {@link
   * #countByUserIdAndAttemptMode}도 개인 학습(FREE)만 세므로, 여기서도 걸러야 한 화면 안에서 전체 합과 단계 합이 같다.
   */
  @Query(
      """
    SELECT p.level AS level,
           COUNT(a) AS solvedCount,
           SUM(CASE WHEN a.correct = true THEN 1L ELSE 0L END) AS correctCount
    FROM ProblemAttempt a
      JOIN Problem p ON p.id = a.problemId
    WHERE a.userId = :userId AND a.attemptMode = com.itda.learning.domain.AttemptMode.FREE
    GROUP BY p.level
    """)
  List<LevelAttemptCount> countByLevel(@Param("userId") Long userId);

  /**
   * 수업 목록 항목의 correctRate 분자·분모. LIVE 풀이만 세고 수업 id로 묶는다.
   *
   * <p>페이지에 실린 수업 id들로 한 번에 묶어 센다 — 항목마다 다시 조회하지 않는다 (API 명세 8.2절).
   */
  @Query(
      """
    SELECT a.lectureId AS lectureId,
           COUNT(a) AS totalCount,
           SUM(CASE WHEN a.correct = true THEN 1L ELSE 0L END) AS correctCount
    FROM ProblemAttempt a
    WHERE a.lectureId IN :lectureIds AND a.attemptMode = com.itda.learning.domain.AttemptMode.LIVE
    GROUP BY a.lectureId
    """)
  List<LectureLiveAttemptCount> countLiveByLectureIdIn(@Param("lectureIds") List<Long> lectureIds);
}
