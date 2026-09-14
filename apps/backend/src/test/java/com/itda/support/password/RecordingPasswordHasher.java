package com.itda.support.password;

import com.itda.user.service.PasswordHasher;
import java.util.ArrayList;
import java.util.List;

/**
 * 호출 여부와 인자를 기록하는 {@link PasswordHasher} 테스트 대역.
 *
 * <p>"정책 위반이면 Argon2를 실행하지 않는다" 같은 계약은 결과값이 아니라 호출 여부로만 확인할 수 있다.
 */
public class RecordingPasswordHasher implements PasswordHasher {

  private final List<String> hashedPasswords = new ArrayList<>();
  private final List<String> verifiedPasswords = new ArrayList<>();
  private boolean matchesResult = true;

  @Override
  public PasswordHasher.EncodedPassword hash(String normalizedPassword) {
    this.hashedPasswords.add(normalizedPassword);
    return PasswordHasher.EncodedPassword.of("$argon2id$v=19$m=19456,t=2,p=1$fake-salt$fake-hash");
  }

  @Override
  public boolean matches(String normalizedPassword, String encodedHash) {
    this.verifiedPasswords.add(normalizedPassword);
    return this.matchesResult;
  }

  public void willReturnMatches(boolean result) {
    this.matchesResult = result;
  }

  public int hashCallCount() {
    return this.hashedPasswords.size();
  }

  public int matchesCallCount() {
    return this.verifiedPasswords.size();
  }

  public List<String> hashedPasswords() {
    return List.copyOf(this.hashedPasswords);
  }

  public List<String> verifiedPasswords() {
    return List.copyOf(this.verifiedPasswords);
  }
}
