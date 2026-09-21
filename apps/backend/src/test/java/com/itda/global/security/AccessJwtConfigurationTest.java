package com.itda.global.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.support.security.AccessTokenTestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AccessJwtConfigurationTest {

  private static MockEnvironment environment(String[] active, String... defaults) {
    MockEnvironment environment = new MockEnvironment();
    if (active.length > 0) {
      environment.setActiveProfiles(active);
    }
    if (defaults.length > 0) {
      environment.setDefaultProfiles(defaults);
    }
    return environment;
  }

  @Test
  void issuer가_실행_프로필과_맞으면_통과한다() {
    AccessJwtConfiguration.requireIssuerMatchesProfile(
        "itda-backend-test", environment(new String[] {"test"}));
    AccessJwtConfiguration.requireIssuerMatchesProfile(
        "itda-backend-local", environment(new String[] {"debug"}, "local"));
  }

  @Test
  void issuer가_실행_프로필과_다르면_기동에_실패한다() {
    assertThatThrownBy(
            () ->
                AccessJwtConfiguration.requireIssuerMatchesProfile(
                    "itda-backend-prod", environment(new String[] {"test"})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("itda.security.jwt.access.issuer가 현재 실행 프로필과 일치하지 않습니다.");
  }

  @Test
  void 실패_message에_issuer나_key_원문이_없다() {
    String key = AccessTokenTestKeys.randomKey();

    assertThatThrownBy(
            () ->
                AccessJwtConfiguration.requireIssuerMatchesProfile(
                    "itda-backend-prod", environment(new String[] {"test"})))
        .hasMessageNotContaining("itda-backend-prod")
        .hasMessageNotContaining(key);
  }
}
