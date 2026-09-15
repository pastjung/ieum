package com.itda.user.dto;

/**
 * {@code POST /api/v1/password-reset-requests} 요청 body (API 명세 3.4.2절).
 *
 * <p><b>{@code email}을 {@code Object}로 받는다.</b> 계약이 "문자열이 아니면 {@code 422 INVALID_ACCOUNT_INPUT}"이라고
 * 정했기 때문이다. {@code String}으로 선언하면 Jackson이 숫자·불리언을 문자열로 바꿔 주거나 객체·배열에서 읽기 자체를 실패시켜({@code 400}) 계약과
 * 어긋난다. 여기서는 JSON 값의 모양만 받아 두고 판정은 {@link #rawEmail()}이 한 자리에서 한다.
 *
 * <p>Bean Validation 애너테이션을 붙이지 않는다. 붙이면 실패가 공통 {@code INVALID_REQUEST_INPUT}으로 나가는데 이 API의 계약은
 * {@code INVALID_ACCOUNT_INPUT}이다.
 *
 * <p>{@code toString()}을 가린다 — 이메일 원문이 들어 있다.
 */
public record PasswordResetMailRequest(Object email) {

  /**
   * JSON 문자열일 때만 값을 돌려준다. trim·소문자 정규화는 application의 {@code Email}이 한다.
   *
   * @return 문자열이 아니거나 없으면 {@code null}. 누락·{@code null}·비문자열이 모두 같은 {@code 422}라 구분하지 않는다
   */
  public String rawEmail() {
    return this.email instanceof String value ? value : null;
  }

  @Override
  public String toString() {
    return "PasswordResetMailRequest[REDACTED]";
  }
}
