package com.itda.user.application;

import com.itda.user.application.port.out.PasswordHasher;
import com.itda.user.domain.PasswordPolicy;

/**
 * 이미 저장된 자격 증명을 검증한다. 로그인 {@code password}와 탈퇴 {@code currentPassword}가 이 경로를 쓴다.
 *
 * <p>새 비밀번호 길이 정책과 유출 조회를 실행하지 않는다. 기존 비밀번호는 과거 정책으로 만들어졌을 수 있고, 로그인마다 외부 조회를 붙이면 조회 장애가 로그인 장애가
 * 된다. 정규화 규칙만 새 비밀번호 경로와 공유해야 같은 입력이 같은 해시와 비교된다.
 *
 * <p>실패를 어떤 HTTP 오류로 옮길지는 이 계층이 정하지 않는다. 로그인은 {@code 401 INVALID_CREDENTIALS}, 탈퇴는 {@code 403
 * CURRENT_PASSWORD_MISMATCH}로 서로 다르며 그 판단은 각 유스케이스의 몫이다.
 */
public class PasswordVerificationService {

  private final PasswordHasher passwordHasher;

  public PasswordVerificationService(PasswordHasher passwordHasher) {
    this.passwordHasher = passwordHasher;
  }

  /**
   * @param encodedHash 저장소에서 읽은 {@code users.password_hash}
   * @return 형식이 유효하지 않거나 일치하지 않으면 false
   */
  public boolean matches(String rawPassword, String encodedHash) {
    return this.passwordHasher.matches(PasswordPolicy.normalize(rawPassword), encodedHash);
  }
}
