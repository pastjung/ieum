package com.itda.user.dto;

/**
 * {@code POST /api/v1/email-verifications} 요청 body (API 명세 3.4.1절).
 *
 * <p>{@code token}을 {@code Object}로 받는 이유는 {@link EmailVerificationMailRequest}와 같다 — 계약이 "문자열이 아니면
 * {@code 422 INVALID_ACCOUNT_INPUT}"이라고 정했다.
 *
 * <p><b>문자열을 가공하지 않는다.</b> token은 opaque 값이라 trim·정규화·대소문자 변환을 적용하지 않는다. 앞뒤 공백이 붙은 non-empty 값도 원문
 * 그대로 넘기며, 그 때문에 JWT 검증에 실패하면 {@code 400 ACTION_TOKEN_INVALID}다.
 *
 * <p>{@code toString()}을 가린다 — 토큰 원문이 들어 있다. 원문은 요청 처리 경로의 메모리에만 존재해야 한다.
 */
public record EmailVerificationCompletionRequest(Object token) {

  /**
   * JSON 문자열일 때만 값을 돌려준다. 가공하지 않는다.
   *
   * @return 문자열이 아니거나 없으면 {@code null}. 누락·{@code null}·비문자열이 모두 같은 {@code 422}라 구분하지 않는다
   */
  public String rawToken() {
    return this.token instanceof String value ? value : null;
  }

  @Override
  public String toString() {
    return "EmailVerificationCompletionRequest[REDACTED]";
  }
}
