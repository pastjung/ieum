package com.itda.learning.domain;

/** 풀이 문맥 (ERD 5.1절). 서버가 파생하며 클라이언트 값을 신뢰하지 않는다. REVIEW는 사용하지 않는다. */
public enum AttemptMode {
  /** 개인 학습·복습. 세션 연결 두 컬럼이 존재하고 lecture_id는 NULL이다. BKT 갱신 대상이다. */
  FREE,
  /** 실시간 수업. lecture_id가 존재하고 세션 연결 두 컬럼은 NULL이다. BKT와 user_skill_memory 갱신에서 제외한다. */
  LIVE
}
