package com.itda.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuntimeEnvironmentProfileTest {

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
  void 활성_프로필에_실행_환경이_하나면_그_값을_쓴다() {
    assertThat(RuntimeEnvironmentProfile.resolve(environment(new String[] {"test"})))
        .isEqualTo("test");
  }

  @Test
  void 보조_프로필이_함께_있어도_실행_환경을_찾는다() {
    assertThat(
            RuntimeEnvironmentProfile.resolve(
                environment(new String[] {"debug", "test", "metrics"})))
        .isEqualTo("test");
  }

  @Test
  void 보조_프로필의_순서에_영향을_받지_않는다() {
    assertThat(RuntimeEnvironmentProfile.resolve(environment(new String[] {"test", "debug"})))
        .isEqualTo("test");
    assertThat(RuntimeEnvironmentProfile.resolve(environment(new String[] {"debug", "test"})))
        .isEqualTo("test");
  }

  @Test
  void 실행_환경_프로필이_둘_이상이면_기동에_실패한다() {
    assertThatThrownBy(
            () -> RuntimeEnvironmentProfile.resolve(environment(new String[] {"local", "prod"})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("둘 이상");
    assertThatThrownBy(
            () ->
                RuntimeEnvironmentProfile.resolve(
                    environment(new String[] {"prod", "debug", "local"})))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 활성_프로필에_실행_환경이_없으면_default에서_찾는다() {
    assertThat(RuntimeEnvironmentProfile.resolve(environment(new String[] {"debug"}, "local")))
        .isEqualTo("local");
    assertThat(RuntimeEnvironmentProfile.resolve(environment(new String[] {}, "local")))
        .isEqualTo("local");
  }

  @Test
  void 지원하는_실행_환경_프로필이_하나도_없으면_기동에_실패한다() {
    assertThatThrownBy(
            () -> RuntimeEnvironmentProfile.resolve(environment(new String[] {"debug"}, "metrics")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("하나로 결정하지 못했");
  }

  @Test
  void default에서도_둘_이상이면_기동에_실패한다() {
    assertThatThrownBy(
            () ->
                RuntimeEnvironmentProfile.resolve(
                    environment(new String[] {"debug"}, "local", "prod")))
        .isInstanceOf(IllegalStateException.class);
  }
}
