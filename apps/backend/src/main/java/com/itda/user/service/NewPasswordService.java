package com.itda.user.service;

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

  /**
   * 새 비밀번호 처리의 닫힌 결과.
   *
   * <p>정책 위반과 조회 장애를 예외가 아니라 값으로 돌려준다. 둘 다 예상되는 결과이고 서로 다른 HTTP 응답({@code 422 INVALID_PASSWORD} vs
   * {@code 503 PASSWORD_POLICY_UNAVAILABLE})으로 이어지므로, 호출자가 빠뜨릴 수 없게 sealed 타입으로 고정한다.
   *
   * <p>어느 구현에도 비밀번호 원문·정규화 값이 들어 있지 않다. {@code Hashed}가 담는 {@link PasswordHasher.EncodedPassword}의
   * {@code toString()}도 가려진 값만 반환하므로 결과를 그대로 찍어도 해시가 노출되지 않는다.
   */
  public sealed interface Result {

    /** 정책을 통과해 해시를 만들었다. */
    record Hashed(PasswordHasher.EncodedPassword encodedPassword) implements Result {}

    /** 결정적인 정책 위반이다. 해시를 만들지 않았다. */
    record Violated(PasswordPolicyViolation violation) implements Result {}

    /** 유출 조회에 실패해 정책을 완성하지 못했다. 위반으로 단정하지 않으며 해시도 만들지 않았다. */
    record Unavailable() implements Result {}
  }

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
  public Result prepare(String rawNewPassword) {
    String normalized = PasswordPolicy.normalize(rawNewPassword);

    Optional<PasswordPolicyViolation> lengthViolation = PasswordPolicy.checkLength(normalized);
    if (lengthViolation.isPresent()) {
      return new Result.Violated(lengthViolation.get());
    }

    CompromisedPasswordChecker.CheckResult checkResult =
        this.compromisedPasswordChecker.check(normalized);
    return switch (checkResult) {
      case COMPROMISED -> new Result.Violated(PasswordPolicyViolation.COMPROMISED);
      case UNAVAILABLE -> new Result.Unavailable();
      case NOT_COMPROMISED -> new Result.Hashed(this.passwordHasher.hash(normalized));
    };
  }
}
