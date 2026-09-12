package com.itda.user.domain;

import java.text.Normalizer;
import java.util.Optional;

/**
 * 새 비밀번호에 적용하는 순수 규칙 (API 명세 3.1·3.4.2절).
 *
 * <p>정규화는 NFC만 수행한다. trim·대소문자 변환을 하지 않는 이유는 비밀번호가 공백과 유니코드를 값의 일부로 허용하기 때문이다. 앞뒤 공백을 지우면 사용자가 입력한
 * 것과 다른 비밀번호가 저장된다.
 *
 * <p>길이는 UTF-16 code unit 수인 {@link String#length()}가 아니라 Unicode code point 수로 센다. 이모지 같은
 * supplementary character 하나가 code unit 2개이므로, code unit으로 세면 같은 글자 수의 비밀번호가 입력 문자 종류에 따라 다르게 판정된다.
 *
 * <p>이 클래스는 정책 위반을 예외가 아니라 {@link PasswordPolicyViolation}으로 돌려준다. 위반은 예상되는 결과이며 호출자가 HTTP 응답으로 옮길
 * 값이다.
 */
public final class PasswordPolicy {

  /** API 명세 3.4.2절: 정규화 후 최소 code point 수. */
  public static final int MIN_CODE_POINTS = 15;

  /** API 명세 3.4.2절: 정규화 후 최대 code point 수. */
  public static final int MAX_CODE_POINTS = 64;

  private PasswordPolicy() {}

  /**
   * NFC로 정규화한다. trim·대소문자 변환·공백 제거를 하지 않는다.
   *
   * @param rawPassword 요청에서 받은 비밀번호. non-null이어야 한다
   */
  public static String normalize(String rawPassword) {
    requireNonNullPassword(rawPassword);
    return Normalizer.normalize(rawPassword, Normalizer.Form.NFC);
  }

  /**
   * 정규화된 비밀번호의 길이 정책을 검사한다.
   *
   * @return 위반이 없으면 빈 Optional. 빈 문자열은 0 code point이므로 {@code TOO_SHORT}다
   */
  public static Optional<PasswordPolicyViolation> checkLength(String normalizedPassword) {
    requireNonNullPassword(normalizedPassword);
    int codePoints = normalizedPassword.codePointCount(0, normalizedPassword.length());
    if (codePoints < MIN_CODE_POINTS) {
      return Optional.of(PasswordPolicyViolation.TOO_SHORT);
    }
    if (codePoints > MAX_CODE_POINTS) {
      return Optional.of(PasswordPolicyViolation.TOO_LONG);
    }
    return Optional.empty();
  }

  /** 예외 message에 비밀번호를 넣지 않는다 — 로그·오류 응답으로 새어 나갈 수 있다. */
  private static void requireNonNullPassword(String password) {
    if (password == null) {
      throw new IllegalArgumentException("비밀번호 값이 없습니다.");
    }
  }
}
