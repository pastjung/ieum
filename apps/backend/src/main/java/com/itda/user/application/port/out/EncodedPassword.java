package com.itda.user.application.port.out;

import java.util.Objects;

/**
 * Argon2id 인코딩 결과를 담는 값 타입. {@code users.password_hash}에 그대로 저장한다.
 *
 * <p>String을 그대로 돌려주지 않는 이유는 {@code toString()} 때문이다. 결과 객체를 로그·디버거·테스트 실패 출력에 찍는 순간 해시 전체가 노출되므로,
 * {@link #toString()}은 항상 가려진 값만 반환하고 실제 값은 {@link #value()}를 명시적으로 호출해야만 얻을 수 있다.
 */
public final class EncodedPassword {

  private static final String REDACTED = "EncodedPassword[REDACTED]";

  private final String value;

  private EncodedPassword(String value) {
    this.value = value;
  }

  public static EncodedPassword of(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("인코딩된 비밀번호 해시가 비어 있습니다.");
    }
    return new EncodedPassword(value);
  }

  /** 영속화처럼 실제 값이 필요한 곳에서만 호출한다. */
  public String value() {
    return this.value;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof EncodedPassword encoded && this.value.equals(encoded.value);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(this.value);
  }

  @Override
  public String toString() {
    return REDACTED;
  }
}
