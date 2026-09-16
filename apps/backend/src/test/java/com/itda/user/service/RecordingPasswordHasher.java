package com.itda.user.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 호출 여부와 인자를 기록하는 {@link PasswordHasher} 테스트 대역.
 *
 * <p>"정책 위반이면 Argon2를 실행하지 않는다" 같은 계약은 결과값이 아니라 호출 여부로만 확인할 수 있다.
 *
 * <p>가입 동시성 테스트가 이 인스턴스를 여러 스레드에서 공유하므로 기록 컬렉션은 {@link CopyOnWriteArrayList}다. 기록·호출 횟수 조회· {@code
 * List.copyOf} 스냅샷이 겹쳐도 예외나 누락이 없고, 스냅샷은 그 시점의 불변 배열을 복사한다. 설정용 setter는 동시 작업을 제출하기 전에 호출하는 것이
 * 계약이며, 실행 중 설정 변경은 지원하지 않는다.
 */
public class RecordingPasswordHasher implements PasswordHasher {

  private final List<String> hashedPasswords = new CopyOnWriteArrayList<>();
  private final List<String> verifiedPasswords = new CopyOnWriteArrayList<>();
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
