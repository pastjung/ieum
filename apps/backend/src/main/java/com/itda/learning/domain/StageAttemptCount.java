package com.itda.learning.domain;

/**
 * 커리큘럼 단계별 풀이 집계.
 *
 * <p>테마 선택 화면이 단계마다 "48문제 중 43개 맞힘"을 보여준다. 풀이 행을 전부 읽어 세면 한 사용자에게도 수백 행이 되므로 DB에서 집계해 가져온다.
 */
public interface StageAttemptCount {

  Long getCurriculumStageId();

  long getSolvedCount();

  long getCorrectCount();
}
