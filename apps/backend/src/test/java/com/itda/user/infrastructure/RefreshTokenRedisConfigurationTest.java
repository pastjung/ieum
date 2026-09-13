package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 인증 상태 키의 prefix가 실행 프로필과 같은지 기동 단계에서 확인한다.
 *
 * <p>prefix는 환경을 가르는 유일한 수단이다. 값이 틀리면 오류가 아니라 "다른 환경의 세션"으로 조용히 동작하므로, 서버가 뜨지 않는 편이 낫다.
 */
class RefreshTokenRedisConfigurationTest {

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

  private static void check(String keyPrefix, MockEnvironment environment) {
    RefreshTokenRedisConfiguration.requireKeyPrefixMatchesProfile(keyPrefix, environment);
  }

  @Test
  void prefix가_실행_프로필과_같으면_통과한다() {
    assertThatCode(() -> check("local", environment(new String[] {"local"})))
        .doesNotThrowAnyException();
    assertThatCode(() -> check("test", environment(new String[] {"test"})))
        .doesNotThrowAnyException();
    assertThatCode(() -> check("prod", environment(new String[] {"prod"})))
        .doesNotThrowAnyException();
  }

  @Test
  void 보조_프로필이_함께_있어도_실행_환경으로_판단한다() {
    assertThatCode(() -> check("test", environment(new String[] {"debug", "test", "metrics"})))
        .doesNotThrowAnyException();
    assertThatCode(() -> check("local", environment(new String[] {"debug"}, "local")))
        .doesNotThrowAnyException();
  }

  @Test
  void prefix가_실행_프로필과_다르면_기동에_실패한다() {
    assertThatThrownBy(() -> check("prod", environment(new String[] {"test"})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("itda.security.auth-redis.key-prefix가 현재 실행 프로필과 일치하지 않습니다.");
    assertThatThrownBy(() -> check("test", environment(new String[] {"local"})))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> check("local", environment(new String[] {"debug"}, "prod")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 실행_환경_프로필이_둘_이상이면_기동에_실패한다() {
    assertThatThrownBy(() -> check("local", environment(new String[] {"local", "prod"})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("둘 이상");
    assertThatThrownBy(() -> check("local", environment(new String[] {"debug"}, "local", "test")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 실행_환경_프로필을_결정하지_못하면_기동에_실패한다() {
    assertThatThrownBy(() -> check("local", environment(new String[] {"debug"}, "metrics")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("하나로 결정하지 못했");
  }

  @Test
  void 실패_message에_prefix나_Redis_주소가_없다() {
    assertThatThrownBy(() -> check("prod", environment(new String[] {"test"})))
        .hasMessageNotContaining("prod")
        .hasMessageNotContaining("localhost")
        .hasMessageNotContaining("6379");
  }
}
