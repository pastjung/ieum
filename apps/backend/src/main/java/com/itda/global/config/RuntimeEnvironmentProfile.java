package com.itda.global.config;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * 실행 환경 프로필({@code local}·{@code test}·{@code prod})을 하나로 결정한다.
 *
 * <p>Access issuer 검증과 인증 Redis key prefix 검증이 같은 규칙으로 환경을 판단해야 하므로 판정을 이 한 곳에 둔다. 두 설정은 서로를 참조하지
 * 않고 이 클래스를 쓴다.
 */
public final class RuntimeEnvironmentProfile {

  /** 배포 환경을 뜻하는 프로필. 나머지 보조 프로필(디버깅·기능 토글 등)은 실행 환경 판단에 쓰지 않는다. */
  private static final Set<String> ENVIRONMENT_PROFILES = Set.of("local", "test", "prod");

  private RuntimeEnvironmentProfile() {}

  /**
   * 실행 환경 프로필을 하나로 결정한다.
   *
   * <p>첫 번째 활성 프로필을 쓰지 않는 이유: 보조 프로필을 함께 켜는 것은 정상적인 사용이고, 그 순서에 따라 issuer 판정이 달라지면 같은 설정이 실행마다 다르게
   * 해석된다. 그래서 배포 환경을 뜻하는 셋 중 몇 개가 켜졌는지로만 판단한다.
   *
   * <p>둘 이상이면 어느 환경인지 단정할 수 없으므로 기동에 실패한다. 잘못 고른 환경으로 토큰을 서명하기 시작하는 것보다 뜨지 않는 편이 낫다.
   */
  public static String resolve(Environment environment) {
    List<String> active = environmentProfilesIn(environment.getActiveProfiles());
    if (active.size() > 1) {
      throw new IllegalStateException("실행 환경 프로필(local, test, prod)이 둘 이상 활성화되어 환경을 결정할 수 없습니다.");
    }
    if (active.size() == 1) {
      return active.get(0);
    }
    List<String> defaults = environmentProfilesIn(environment.getDefaultProfiles());
    if (defaults.size() != 1) {
      throw new IllegalStateException("실행 환경 프로필(local, test, prod)을 하나로 결정하지 못했습니다.");
    }
    return defaults.get(0);
  }

  private static List<String> environmentProfilesIn(String[] profiles) {
    return Arrays.stream(profiles).filter(ENVIRONMENT_PROFILES::contains).distinct().toList();
  }
}
