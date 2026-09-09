package com.itda.lecture.domain;

/**
 * 수업 진행 상태 (ERD 3.2절). SCHEDULED → OPEN → CLOSED로만 이동하며 되돌아가지 않는다.
 *
 * <p>MVP에는 취소·삭제·재개·CANCELLED가 없다.
 */
public enum LectureStatus {
  /** 시작 전. started_at·ended_at이 모두 NULL이며 제목·설명·예정 시각을 수정할 수 있다. */
  SCHEDULED,
  /** 진행 중. started_at만 존재한다. */
  OPEN,
  /** 종료. started_at·ended_at이 모두 존재하고 현재 문제는 NULL이 된다. */
  CLOSED
}
