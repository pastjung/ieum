package com.itda.user.application.port.out;

/** 계열 폐기 결과 ({@code revokeFamily}). 계열 hash의 상태만 바꾸며 토큰 상태는 건드리지 않는다. */
public enum RefreshFamilyRevocationResult {
  REVOKED,
  NOT_FOUND,
  UNAVAILABLE
}
