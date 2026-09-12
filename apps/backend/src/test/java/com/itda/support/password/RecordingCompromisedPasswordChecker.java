package com.itda.support.password;

import com.itda.user.application.port.out.CompromisedPasswordCheckResult;
import com.itda.user.application.port.out.CompromisedPasswordChecker;
import java.util.ArrayList;
import java.util.List;

/** 호출 여부와 인자를 기록하는 {@link CompromisedPasswordChecker} 테스트 대역. 외부 네트워크를 쓰지 않는다. */
public class RecordingCompromisedPasswordChecker implements CompromisedPasswordChecker {

  private final List<String> checkedPasswords = new ArrayList<>();
  private CompromisedPasswordCheckResult result = CompromisedPasswordCheckResult.NOT_COMPROMISED;

  @Override
  public CompromisedPasswordCheckResult check(String normalizedPassword) {
    this.checkedPasswords.add(normalizedPassword);
    return this.result;
  }

  public void willReturn(CompromisedPasswordCheckResult result) {
    this.result = result;
  }

  public int checkCallCount() {
    return this.checkedPasswords.size();
  }

  public List<String> checkedPasswords() {
    return List.copyOf(this.checkedPasswords);
  }
}
