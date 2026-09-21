package com.itda.user.domain;

import java.text.Normalizer;

/**
 * 계정 생성 입력의 아이디 규칙 (API 명세 3.2.1절).
 *
 * <p>trim·NFC 정규화 후 3~30자이며 ASCII 영문 대소문자·숫자·{@code .}·{@code _}·{@code -}만 허용하고 처음과 끝은 ASCII 영문 또는
 * 숫자다. 정규화를 검증보다 먼저 하는 이유는 계약이 "정규화 후" 길이와 문자를 판정하도록 정하기 때문이다 — 순서를 바꾸면 같은 입력이 정규화 형태에 따라 다르게 판정된다.
 *
 * <p><b>{@link User}의 저장 규칙과 의도적으로 다르다.</b> 여기는 <em>공개 가입이 받는 입력</em>의 계약이고, 엔티티는 {@code
 * VARCHAR(50)}이라는 <em>저장 한계</em>를 지킨다. 탈퇴 가명 식별자처럼 사용자가 입력하지 않는 값은 이 규칙이 아니라 저장 한계만 만족하면 되므로, 둘을 한
 * 규칙으로 묶으면 가명 생성 방식이 가입 입력 규칙에 끌려간다.
 *
 * <p>대소문자 비구분 UNIQUE는 여기서 다루지 않는다. 값을 소문자로 바꾸면 사용자가 입력한 표기를 잃으므로, 표기는 그대로 저장하고 중복 판정은 MariaDB의 대소문자
 * 무시 collation과 UNIQUE 제약에 맡긴다 (ERD 3.1절).
 *
 * <p>예외 message에 입력값을 넣지 않는다. 이 예외는 로그·모니터링·상위 예외 message로 흘러갈 수 있다.
 */
public final class Username {

  /** API 명세 3.2.1절: 정규화 후 3자 이상. */
  public static final int MIN_LENGTH = 3;

  /** API 명세 3.2.1절: 정규화 후 30자 이하. {@link User}의 저장 한계(50)와 다르다. */
  public static final int MAX_LENGTH = 30;

  private static final String ALLOWED = "A-Za-z0-9._-";
  private static final String EDGE = "A-Za-z0-9";

  /** 길이는 따로 센다. 정규식에 길이를 넣으면 "3~30자 위반"과 "문자 위반"의 원인이 한 덩어리가 된다. */
  private static final java.util.regex.Pattern PATTERN =
      java.util.regex.Pattern.compile("^[" + EDGE + "](?:[" + ALLOWED + "]*[" + EDGE + "])?$");

  private final String value;

  private Username(String value) {
    this.value = value;
  }

  /**
   * 원문을 정규화하고 계약 규칙을 검사한다.
   *
   * @param rawValue 요청에서 받은 아이디. {@code null}·공백만 있는 값도 여기서 거부한다
   * @throws IllegalArgumentException 규칙을 어겼을 때. message에 입력값을 담지 않는다
   */
  public static Username of(String rawValue) {
    if (rawValue == null) {
      throw new IllegalArgumentException("아이디는 필수입니다.");
    }
    String normalized = Normalizer.normalize(rawValue.trim(), Normalizer.Form.NFC);
    // 길이는 code unit이 아니라 code point로 센다. 허용 문자가 ASCII뿐이라 결과는 같지만,
    // 거부돼야 할 비ASCII 입력의 길이를 먼저 잘못 세어 엉뚱한 이유로 통과·거부하지 않게 한다.
    int length = normalized.codePointCount(0, normalized.length());
    if (length < MIN_LENGTH || length > MAX_LENGTH) {
      throw new IllegalArgumentException("아이디는 " + MIN_LENGTH + "~" + MAX_LENGTH + "자여야 합니다.");
    }
    if (!PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException("아이디에 사용할 수 없는 문자가 있습니다.");
    }
    return new Username(normalized);
  }

  public String value() {
    return this.value;
  }

  /** 아이디는 개인정보다. 로그·예외에 그대로 찍히지 않게 가린다. */
  @Override
  public String toString() {
    return "Username[REDACTED]";
  }
}
