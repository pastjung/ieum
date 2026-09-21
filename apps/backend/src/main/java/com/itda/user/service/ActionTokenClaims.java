package com.itda.user.service;

/**
 * 계정 작업 토큰 발급 입력 (API 명세 3.6.1절 Payload claim).
 *
 * <p>용도는 담지 않는다. 발급기가 용도별로 분리돼 있어 호출한 port가 곧 용도이며, 값으로 넘기면 "범용 발급기에 종류를 파라미터로 넘기지 않는다"는 계약(3.6.1절
 * 「종류 분리」)을 우회하는 통로가 된다.
 *
 * <p>{@code authVersion}·{@code role}·{@code familyId}·이메일·username을 담지 않는다. 계약의 action claim 집합에
 * 없고, 메일로 나가는 토큰에 개인정보를 싣지 않기 위해서다.
 *
 * <p>{@code jti}·{@code iat}·{@code exp}도 담지 않는다 — 서버가 발급 시점에 직접 만든다.
 */
public record ActionTokenClaims(long userId) {

  public ActionTokenClaims {
    if (userId <= 0) {
      throw new IllegalArgumentException("사용자 식별자가 올바르지 않습니다.");
    }
  }

  /** 사용자 식별자가 로그로 새지 않게 한다. */
  @Override
  public String toString() {
    return "ActionTokenClaims[REDACTED]";
  }
}
