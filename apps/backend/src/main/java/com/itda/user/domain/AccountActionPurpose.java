package com.itda.user.domain;

/**
 * 계정 작업 토큰과 메일 발송의 용도 (API 명세 3.6.1절, ERD 3.1.1·3.5절).
 *
 * <p>두 곳이 같은 값을 쓰므로 열거를 하나만 둔다. 계정 작업 JWT의 {@code purpose} claim, Redis action key의 용도 자리, {@code
 * mail_outbox.purpose}가 모두 이 값이다. 나눠 두면 같은 개념이 세 벌이 되고 변환 지점마다 어긋날 자리가 생긴다.
 *
 * <p>용도별 유효기간·{@code typ}·{@code aud} 같은 토큰 계약 값은 여기에 두지 않는다. 그것은 JWT 구현의 몫이고, 도메인은 "어떤 용도인가"만 안다.
 */
public enum AccountActionPurpose {

  /** 이메일 인증. 회원가입 인증 메일도 이 용도를 쓴다 (ERD 3.5절). */
  EMAIL_VERIFICATION,

  /** 비밀번호 재설정. */
  PASSWORD_RESET
}
