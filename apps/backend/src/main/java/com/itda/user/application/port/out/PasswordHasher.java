package com.itda.user.application.port.out;

/**
 * 비밀번호 해시 생성·검증 outbound port. 구현은 {@code user/infrastructure}에 둔다.
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
}
