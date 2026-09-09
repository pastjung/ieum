package com.itda.problem.domain;

/**
 * 문제 출처 (ERD 4.3절). AI 서비스가 아니라 최종 저장을 수행하는 Backend가 결정한다.
 *
 * <p>SYSTEM·AI 문제는 creator_user_id와 creation_idempotency_key가 모두 NULL이다.
 */
public enum ProblemSourceType {
  /** 강사가 공개 등록한 문제. creator_user_id와 멱등 키가 있다. */
  INSTRUCTOR,
  /** 버전 Seed·일회성 마이그레이션으로 등록한 기본 문제. */
  SYSTEM,
  /** Backend 전용 내부 후보 생성·검증을 거친 문제. AI 서비스는 DB에 직접 쓰지 않는다. */
  AI
}
