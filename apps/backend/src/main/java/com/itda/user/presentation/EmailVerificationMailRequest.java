package com.itda.user.presentation;

/**
 * {@code POST /api/v1/email-verification-requests} 요청 body (API 명세 3.4.1절).
 *
 * <p><b>{@code email}을 {@code Object}로 받는다.</b> 계약이 "문자열이 아니면 {@code 422 INVALID_ACCOUNT_INPUT}"이라고
 * 정했기 때문이다. {@code String}으로 선언하면 Jackson이 숫자·불리언을 문자열로 바꿔 주거나(형식 위반 422로 둔갑) 객체·배열에서 읽기 자체를
 * 실패시켜({@code 400}) 계약과 어긋난다. 여기서는 JSON 값의 <b>모양</b>만 받아 두고 판정은 {@link #rawEmail()}이 한 자리에서 한다.
 *
 * <p>같은 이유로 회원가입의 문자열 전용 deserializer를 여기에 재사용하지 않는다. 그쪽 계약은 타입 불일치가 {@code 400}이고 이쪽은 {@code 422}다
 * — 두 API의 계약이 다르므로 구현도 달라야 한다.
 *
 * <p>JSON 문법 자체가 깨진 요청은 이 타입에 닿기 전에 {@code 400 MALFORMED_REQUEST_BODY}가 된다.
 *
 * <p>Bean Validation 애너테이션을 붙이지 않는다. 붙이면 실패가 공통 {@code INVALID_REQUEST_INPUT}으로 나가는데 이 API의 계약은
 * {@code INVALID_ACCOUNT_INPUT}이다.
 *
 * <p>{@code toString()}을 가린다 — 이메일 원문이 들어 있다.
 */
public record EmailVerificationMailRequest(Object email) {

  /**
   * JSON 문자열일 때만 값을 돌려준다.
   *
   * @return 문자열이 아니거나 없으면 {@code null}. 누락·{@code null}·비문자열이 모두 같은 {@code 422}라 구분하지 않는다
   */
  public String rawEmail() {
    return this.email instanceof String value ? value : null;
  }

  @Override
  public String toString() {
    return "EmailVerificationMailRequest[REDACTED]";
  }
}
