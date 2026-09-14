package com.itda.support.password;

import com.itda.user.service.CompromisedPasswordChecker;
import java.util.ArrayList;
import java.util.List;

/** 호출 여부와 인자를 기록하는 {@link CompromisedPasswordChecker} 테스트 대역. 외부 네트워크를 쓰지 않는다. */
public class RecordingCompromisedPasswordChecker implements CompromisedPasswordChecker {

  private final List<String> checkedPasswords = new ArrayList<>();
  private CompromisedPasswordChecker.CheckResult result =
      CompromisedPasswordChecker.CheckResult.NOT_COMPROMISED;

  @Override
  public CompromisedPasswordChecker.CheckResult check(String normalizedPassword) {
    this.checkedPasswords.add(normalizedPassword);
    return this.result;
  }

  public void willReturn(CompromisedPasswordChecker.CheckResult result) {
    this.result = result;
  }

  public int checkCallCount() {
    return this.checkedPasswords.size();
  }

  public List<String> checkedPasswords() {
    return List.copyOf(this.checkedPasswords);
  }
}
