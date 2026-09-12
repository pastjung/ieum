package com.itda.user.application;

import com.itda.user.application.port.out.CompromisedPasswordCheckResult;
import com.itda.user.application.port.out.CompromisedPasswordChecker;
import com.itda.user.application.port.out.PasswordHasher;
import com.itda.user.domain.PasswordPolicy;
import com.itda.user.domain.PasswordPolicyViolation;
import java.util.Optional;

/**
 * 새 비밀번호에 전체 정책을 적용하고 해시까지 만든다. 회원가입 {@code password}와 비밀번호 재설정 {@code newPassword}가 이 경로를 쓴다.
 *
 * <p>기존 자격 증명 검증(로그인·탈퇴)은 이 서비스를 쓰지 않는다. 그 경로는 {@link PasswordVerificationService}이며, 길이 정책과 유출 조회를
 * 실행하지 않는다.
 *
 * <p>단계 순서에는 이유가 있다. 결정적인 로컬 위반을 먼저 확정해 불필요한 외부 조회를 막고, 유출 조회가 끝난 뒤에야 Argon2id를 실행한다. API 명세
 * 3.4.2절이 정책 검증을 해시보다 먼저, 해시를 트랜잭션과 action token 소비보다 먼저 두도록 요구한다.
 */
public class NewPasswordService {

  private final CompromisedPasswordChecker compromisedPasswordChecker;
  private final PasswordHasher passwordHasher;

  public NewPasswordService(
      CompromisedPasswordChecker compromisedPasswordChecker, PasswordHasher passwordHasher) {
    this.compromisedPasswordChecker = compromisedPasswordChecker;
    this.passwordHasher = passwordHasher;
  }

  /**
   * 정규화 → 길이 → 유출 조회 → Argon2id 해시 순서로 처리한다.
   *
   * @param rawNewPassword 요청에서 받은 새 비밀번호. 필드 존재·타입·null 검사는 presentation의 책임이다
   */
  public NewPasswordResult prepare(String rawNewPassword) {
    String normalized = PasswordPolicy.normalize(rawNewPassword);

    Optional<PasswordPolicyViolation> lengthViolation = PasswordPolicy.checkLength(normalized);
    if (lengthViolation.isPresent()) {
      return new NewPasswordResult.Violated(lengthViolation.get());
    }

    CompromisedPasswordCheckResult checkResult = this.compromisedPasswordChecker.check(normalized);
    return switch (checkResult) {
      case COMPROMISED -> new NewPasswordResult.Violated(PasswordPolicyViolation.COMPROMISED);
      case UNAVAILABLE -> new NewPasswordResult.Unavailable();
      case NOT_COMPROMISED -> new NewPasswordResult.Hashed(this.passwordHasher.hash(normalized));
    };
  }
}
