package com.itda.user.domain;

/**
 * 사용자 역할. 한 계정은 이 중 하나만 가진다 (ERD 3.1절).
 *
 * <p>공개 가입은 LEARNER만 생성한다. INSTRUCTOR·ADMIN은 관리자 통제 등록이나 초기 데이터로만 만든다.
 */
public enum UserRole {
  LEARNER,
  INSTRUCTOR,
  ADMIN
}
