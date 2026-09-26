package com.itda.user.domain;

/**
 * 계정 상태. 역할·이메일 인증 여부와 별개다 (ERD 3.1절).
 *
 * <p>탈퇴는 행 물리 삭제가 아니라 WITHDRAWN 전이다. 학습·수업 기록은 가명 상태로 보존한다.
 */
public enum UserStatus {
  ACTIVE,
  WITHDRAWN
}
