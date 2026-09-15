package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.user.domain.PasswordPolicyViolation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NewPasswordServiceTest {

  private static final String VALID_PASSWORD = "충분히 긴 새 비밀번호 문구";

  private RecordingCompromisedPasswordChecker compromisedPasswordChecker;
  private RecordingPasswordHasher passwordHasher;
  private NewPasswordService newPasswordService;

  @BeforeEach
  void setUp() {
    this.compromisedPasswordChecker = new RecordingCompromisedPasswordChecker();
    this.passwordHasher = new RecordingPasswordHasher();
    this.newPasswordService =
        new NewPasswordService(this.compromisedPasswordChecker, this.passwordHasher);
  }

  @Test
  void 정책을_통과하면_해시를_만든다() {
    NewPasswordService.Result result = this.newPasswordService.prepare(VALID_PASSWORD);

    assertThat(result).isInstanceOf(NewPasswordService.Result.Hashed.class);
    assertThat(this.compromisedPasswordChecker.checkCallCount()).isEqualTo(1);
    assertThat(this.passwordHasher.hashCallCount()).isEqualTo(1);
  }

  @Test
  void 유출_조회와_해시에는_정규화된_값을_넘긴다() {
    String decomposed = "é".repeat(20);

    this.newPasswordService.prepare(decomposed);

    String normalized = "é".repeat(20);
    assertThat(this.compromisedPasswordChecker.checkedPasswords()).containsExactly(normalized);
    assertThat(this.passwordHasher.hashedPasswords()).containsExactly(normalized);
  }

  @Test
  void 길이_위반이면_유출_조회와_해시를_실행하지_않는다() {
    NewPasswordService.Result result = this.newPasswordService.prepare("짧은비밀번호");

    assertThat(result)
        .isEqualTo(new NewPasswordService.Result.Violated(PasswordPolicyViolation.TOO_SHORT));
    assertThat(this.compromisedPasswordChecker.checkCallCount()).isZero();
    assertThat(this.passwordHasher.hashCallCount()).isZero();
  }

  @Test
  void 최대_길이_초과도_결정적_위반이라_외부_조회를_하지_않는다() {
    NewPasswordService.Result result = this.newPasswordService.prepare("a".repeat(65));

    assertThat(result)
        .isEqualTo(new NewPasswordService.Result.Violated(PasswordPolicyViolation.TOO_LONG));
    assertThat(this.compromisedPasswordChecker.checkCallCount()).isZero();
    assertThat(this.passwordHasher.hashCallCount()).isZero();
  }

  @Test
  void 유출된_비밀번호는_COMPROMISED_위반이며_해시를_만들지_않는다() {
    this.compromisedPasswordChecker.willReturn(CompromisedPasswordChecker.CheckResult.COMPROMISED);

    NewPasswordService.Result result = this.newPasswordService.prepare(VALID_PASSWORD);

    assertThat(result)
        .isEqualTo(new NewPasswordService.Result.Violated(PasswordPolicyViolation.COMPROMISED));
    assertThat(this.passwordHasher.hashCallCount()).isZero();
  }

  @Test
  void 유출_조회가_불가능하면_Unavailable이며_해시를_만들지_않는다() {
    this.compromisedPasswordChecker.willReturn(CompromisedPasswordChecker.CheckResult.UNAVAILABLE);

    NewPasswordService.Result result = this.newPasswordService.prepare(VALID_PASSWORD);

    assertThat(result).isInstanceOf(NewPasswordService.Result.Unavailable.class);
    assertThat(this.passwordHasher.hashCallCount()).isZero();
  }

  @Test
  void 조회_장애를_정책_위반으로_바꾸지_않는다() {
    this.compromisedPasswordChecker.willReturn(CompromisedPasswordChecker.CheckResult.UNAVAILABLE);

    NewPasswordService.Result result = this.newPasswordService.prepare(VALID_PASSWORD);

    assertThat(result).isNotInstanceOf(NewPasswordService.Result.Violated.class);
  }

  @Test
  void 기존_비밀번호와_같은_값이어도_정책만_통과하면_허용한다() {
    NewPasswordService.Result first = this.newPasswordService.prepare(VALID_PASSWORD);
    NewPasswordService.Result second = this.newPasswordService.prepare(VALID_PASSWORD);

    assertThat(first).isInstanceOf(NewPasswordService.Result.Hashed.class);
    assertThat(second).isInstanceOf(NewPasswordService.Result.Hashed.class);
  }

  @Test
  void 앞뒤_공백이_있는_비밀번호를_trim하지_않고_그대로_처리한다() {
    String withSpaces = "  " + "a".repeat(15) + "  ";

    this.newPasswordService.prepare(withSpaces);

    assertThat(this.passwordHasher.hashedPasswords()).containsExactly(withSpaces);
  }
}
