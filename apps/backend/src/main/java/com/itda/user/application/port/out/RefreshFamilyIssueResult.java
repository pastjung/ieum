package com.itda.user.application.port.out;

/**
 * 새 계열과 첫 Refresh 등록 결과 ({@code issueRefreshFamily}).
 *
 * <p>{@code ALREADY_EXISTS}는 같은 {@code familyId} 또는 {@code jti}가 이미 있어 덮어쓰기를 거부한 경우다. 새 식별자를 만들어 다시
 * 시도하는 책임은 이 port가 아니라 호출하는 유스케이스에 있다.
 */
public enum RefreshFamilyIssueResult {
  ISSUED,
  ALREADY_EXISTS,
  UNAVAILABLE
}
