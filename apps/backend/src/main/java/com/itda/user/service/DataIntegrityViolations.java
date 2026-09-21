package com.itda.user.service;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 저장소 무결성 위반의 종류를 판정한다. 회원가입의 식별자 중복과 탈퇴의 가명 충돌이 같은 규칙을 쓴다.
 *
 * <p>제약 <b>이름</b>으로 판정하지 않는다. 이름은 스키마를 누가 언제 만들었는지에 따라 달라진다 — 같은 {@code users.username} UNIQUE가 기존
 * DB에서는 Hibernate가 지은 {@code UKr43af9ap4edm43mmtq01oddj6}이고 새로 만든 DB에서는 다른 값일 수 있다. 이름을 코드에 적어 두면 그
 * 목록에 없는 DB에서 중복이 조용히 {@code 503}이 된다.
 *
 * <p>대신 방언이 채워 주는 {@link ConstraintKind}를 본다. {@code UNIQUE}면 그 문장은 <b>확실히</b> 실패했다 — 결과가 불확실한 상황이
 * 아니다.
 *
 * <p>예외 message를 보지 않는다. MariaDB는 {@code Duplicate entry '...' for key '...'}처럼 <b>중복된 값 자체</b>를
 * message에 담으므로, 그 문자열을 판정에 쓰면 값이 로그·예외 사슬로 흘러갈 경로가 생긴다.
 */
final class DataIntegrityViolations {

  private DataIntegrityViolations() {}

  /** UNIQUE 제약 위반임이 <b>DB가 말해 줘서</b> 확정된 경우만 참이다. NOT NULL·CHECK·길이 위반은 거짓이다. */
  static boolean isUniqueViolation(DataIntegrityViolationException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        return violation.getKind() == ConstraintKind.UNIQUE;
      }
    }
    return false;
  }
}
