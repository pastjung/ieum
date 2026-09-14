package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.password.RecordingPasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasswordVerificationServiceTest {

  private static final String STORED_HASH = "$argon2id$v=19$m=19456,t=2,p=1$salt$hash";

  private RecordingPasswordHasher passwordHasher;
  private PasswordVerificationService passwordVerificationService;

  @BeforeEach
  void setUp() {
    this.passwordHasher = new RecordingPasswordHasher();
    this.passwordVerificationService = new PasswordVerificationService(this.passwordHasher);
  }

  @Test
  void 저장된_해시와_일치하면_true다() {
    this.passwordHasher.willReturnMatches(true);

    assertThat(this.passwordVerificationService.matches("아무 비밀번호", STORED_HASH)).isTrue();
  }

  @Test
  void 일치하지_않으면_false다() {
    this.passwordHasher.willReturnMatches(false);

    assertThat(this.passwordVerificationService.matches("아무 비밀번호", STORED_HASH)).isFalse();
  }

  @Test
  void 검증에는_NFC_정규화된_값을_넘긴다() {
    this.passwordVerificationService.matches("é".repeat(20), STORED_HASH);

    assertThat(this.passwordHasher.verifiedPasswords()).containsExactly("é".repeat(20));
  }

  @Test
  void 새_비밀번호_길이_정책을_적용하지_않는다() {
    this.passwordHasher.willReturnMatches(true);

    // 14 code point는 새 비밀번호였다면 TOO_SHORT지만, 기존 자격 증명 검증은 그대로 진행한다.
    assertThat(this.passwordVerificationService.matches("a".repeat(14), STORED_HASH)).isTrue();
    assertThat(this.passwordVerificationService.matches("a".repeat(65), STORED_HASH)).isTrue();
    assertThat(this.passwordHasher.matchesCallCount()).isEqualTo(2);
  }

  @Test
  void 새_해시를_만들지_않는다() {
    this.passwordVerificationService.matches("아무 비밀번호", STORED_HASH);

    assertThat(this.passwordHasher.hashCallCount()).isZero();
  }

  @Test
  void 앞뒤_공백을_trim하지_않는다() {
    this.passwordVerificationService.matches("  공백 포함  ", STORED_HASH);

    assertThat(this.passwordHasher.verifiedPasswords()).containsExactly("  공백 포함  ");
  }
}
