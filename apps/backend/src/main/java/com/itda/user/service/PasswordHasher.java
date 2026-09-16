package com.itda.user.service;

import java.util.Objects;

/**
 * 비밀번호 해시 생성·검증 outbound port. 구현은 {@link Argon2idPasswordHasher}다.
 *
 * <p>입력은 {@link com.itda.user.domain.PasswordPolicy#normalize(String)}로 정규화된 값이어야 한다. 정규화 위치를 어댑터마다
 * 되풀이하면 경로에 따라 다른 값이 해시된다.
 */
public interface PasswordHasher {

  /** 정규화된 비밀번호의 새 해시를 만든다. 같은 비밀번호라도 salt가 달라 호출마다 결과가 다르다. */
  EncodedPassword hash(String normalizedPassword);

  /**
   * 저장된 해시와 일치하는지 검증한다.
   *
   * @param encodedHash 저장소에서 읽은 값. 신뢰하지 않고 형식을 먼저 검사한다
   * @return 형식이 유효하지 않거나 일치하지 않으면 false
   */
  boolean matches(String normalizedPassword, String encodedHash);

  /**
   * Argon2id 인코딩 결과를 담는 값 타입. {@code users.password_hash}에 그대로 저장한다.
   *
   * <p>String을 그대로 돌려주지 않는 이유는 {@code toString()} 때문이다. 결과 객체를 로그·디버거·테스트 실패 출력에 찍는 순간 해시 전체가
   * 노출되므로, {@link #toString()}은 항상 가려진 값만 반환하고 실제 값은 {@link #value()}를 명시적으로 호출해야만 얻을 수 있다.
   */
  final class EncodedPassword {

    /** 출력 문자열은 통합 전 top-level 타입 이름을 유지한다. */
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
}
