package com.itda.learning.domain;

import com.itda.problem.domain.Level;

/**
 * 문제 단계(Level)별 풀이 집계.
 *
 * <p>"내 실력" 화면이 단계마다 "48문제 중 43개 맞힘"을 보여준다. 풀이 행을 전부 읽어 세면 한 사용자에게도 수백 행이 되므로 DB에서 집계해 가져온다 —
 * {@link StageAttemptCount}와 같은 이유·같은 모양이다.
 *
 * <p>묶는 축이 {@link StageAttemptCount}와 다르다. 저쪽은 <b>세션이 어느 단계였나</b>({@code
 * problem_attempts.curriculum_stage_id})로 묶어 복습 세션 풀이가 통째로 빠지지만, 이쪽은 <b>문제 자신의 단계</b>({@code
 * problems.level})로 묶어 복습 풀이도 제자리에 들어간다.
 */
public interface LevelAttemptCount {

  Level getLevel();

  long getSolvedCount();

  long getCorrectCount();
}
