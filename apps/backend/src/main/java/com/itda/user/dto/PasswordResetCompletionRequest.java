package com.itda.user.dto;

import com.itda.user.service.PasswordResetService;

/**
 * {@code POST /api/v1/password-resets} 요청 body (API 명세 3.4.2절).
 *
 * <p>두 필드를 {@code Object}로 받는 이유는 {@link PasswordResetMailRequest}와 같다 — 계약이 "문자열이 아니면 {@code 422
 * INVALID_ACCOUNT_INPUT}"이라고 정했다.
 *
 * <p><b>문자열을 가공하지 않는다.</b> token은 opaque 값이라 trim·정규화·대소문자 변환을 적용하지 않는다. 새 비밀번호는 공백을 허용하므로 앞뒤 공백도
 * 값의 일부다. 빈 값·공백 값이 required 위반인지 비밀번호 정책 위반인지는 Service가 필드별로 가른다.
 *
 * <p>{@code toString()}을 가린다 — 토큰 원문과 새 비밀번호가 들어 있다.
 */
public record PasswordResetCompletionRequest(Object token, Object newPassword) {

  /** 문자열이 아니거나 없는 필드는 {@code null}로 접어 넘긴다. */
  public PasswordResetService.Command toCommand() {
    return new PasswordResetService.Command(
        stringOrNull(this.token), stringOrNull(this.newPassword));
  }

  private static String stringOrNull(Object value) {
    return value instanceof String text ? text : null;
  }

  @Override
  public String toString() {
    return "PasswordResetCompletionRequest[REDACTED]";
  }
}
