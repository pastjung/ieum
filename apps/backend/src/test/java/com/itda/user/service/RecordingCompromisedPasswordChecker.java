package com.itda.user.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 호출 여부와 인자를 기록하는 {@link CompromisedPasswordChecker} 테스트 대역. 외부 네트워크를 쓰지 않는다.
 *
 * <p>가입 동시성 테스트가 {@link NewPasswordService}를 통해 이 인스턴스를 여러 스레드에서 공유하므로 기록 컬렉션은 {@link
 * CopyOnWriteArrayList}다. 기록·호출 횟수 조회·{@code List.copyOf} 스냅샷이 겹쳐도 예외나 누락이 없다. 설정용 setter는 동시 작업을
 * 제출하기 전에 호출하는 것이 계약이며, 실행 중 설정 변경은 지원하지 않는다.
 */
public class RecordingCompromisedPasswordChecker implements CompromisedPasswordChecker {

  private final List<String> checkedPasswords = new CopyOnWriteArrayList<>();
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
