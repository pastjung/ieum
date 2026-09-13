package com.itda.user.application;

/**
 * 회원가입 입력 (API 명세 3.2.1절).
 *
 * <p>세 값만 받는다. {@code role}·{@code status}·{@code emailVerified}·{@code level}·{@code
 * authVersion}·{@code rowVersion}은 서버가 정하므로 이 타입에 자리가 없다 — 클라이언트가 무엇을 보내든 생성되는 계정 값에 닿을 수 없다.
 *
 * <p>정규화·형식 검증을 여기서 하지 않는다. {@code null}과 원문을 그대로 받아 {@link UserRegistrationService}가 한 자리에서 판정한다.
 * 값 객체 생성 시점이 흩어지면 "무엇이 어떤 오류 code가 되는지"가 계층마다 달라진다.
 *
 * <p>{@code toString()}을 가린다. 비밀번호 원문과 개인정보가 들어 있어 로그·예외에 찍히면 안 된다.
 */
public record UserRegistrationCommand(String username, String email, String password) {

  @Override
  public String toString() {
    return "UserRegistrationCommand[REDACTED]";
  }
}
