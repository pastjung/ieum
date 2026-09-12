package com.itda.user.application.port.out;

/** 새 비밀번호가 알려진 유출 목록에 있는지 확인하는 outbound port. */
public interface CompromisedPasswordChecker {

  /**
   * 정규화된 비밀번호를 조회한다.
   *
   * @return 조회 자체가 불가능하면 {@link CompromisedPasswordCheckResult#UNAVAILABLE}. 판정을 추측하지 않는다
   */
  CompromisedPasswordCheckResult check(String normalizedPassword);
}
