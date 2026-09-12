package com.itda.support.security;

import com.itda.user.application.port.out.AccessTokenVerification;
import com.itda.user.application.port.out.AccessTokenVerifier;
import java.util.ArrayList;
import java.util.List;

/** 검증 결과를 고정하고 호출을 기록하는 테스트 대역. 실제 서명 연산 없이 인증 판정만 검증할 때 쓴다. */
public class StubAccessTokenVerifier implements AccessTokenVerifier {

  private final List<String> verifiedTokens = new ArrayList<>();
  private AccessTokenVerification result = new AccessTokenVerification.Invalid();

  @Override
  public AccessTokenVerification verify(String compactToken) {
    this.verifiedTokens.add(compactToken);
    return this.result;
  }

  public void willReturn(AccessTokenVerification result) {
    this.result = result;
  }

  public List<String> verifiedTokens() {
    return List.copyOf(this.verifiedTokens);
  }
}
