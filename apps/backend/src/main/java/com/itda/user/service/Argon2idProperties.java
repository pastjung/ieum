package com.itda.user.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Argon2id 비용 파라미터. 기본값은 기능 명세 2.1절이 정한 최소 설정(메모리 19MiB·반복 2·병렬도 1)이다.
 *
 * <p>배포 환경에서 응답성과 동시 부하를 측정해 상향할 수 있으므로 설정으로 열어 두지만, 하한과 상한은 코드가 강제한다. 하한은 계약 위반을 막고, 상한은 오타 하나로
 * 서버가 메모리를 고갈시키는 것을 막는다.
 *
 * <p>검증을 생성자에서 하는 이유는 fail-fast다. 비밀번호를 처리하는 시점에 확인하면 잘못된 설정이 운영에 올라간 뒤 첫 가입 요청에서야 드러난다.
 */
@ConfigurationProperties("itda.security.password.argon2")
public record Argon2idProperties(
    @DefaultValue("19456") int memoryKib,
    @DefaultValue("2") int iterations,
    @DefaultValue("1") int parallelism) {

  // 아래 상수는 "새 해시를 만들 때 허용하는 설정 범위"다. 이미 저장된 해시를 검증할 수 있는 범위는 별개이며
  // Argon2idPasswordHasher의 VERIFIABLE_* 상수가 가진다. 지금은 값이 같지만 두 범위를 하나로 합치지 않는다 —
  // 생성 하한을 올렸을 때 기존 해시까지 검증 불가가 되면 로그인도, 로그인해야 가능한 재해시도 막힌다.

  /** 기능 명세 2.1절: 19MiB. Argon2 파라미터 단위가 KiB이므로 19 * 1024다. */
  public static final int GENERATION_MIN_MEMORY_KIB = 19456;

  /** 한 요청이 잡을 수 있는 메모리 상한 (256MiB). */
  public static final int GENERATION_MAX_MEMORY_KIB = 262144;

  public static final int GENERATION_MIN_ITERATIONS = 2;
  public static final int GENERATION_MAX_ITERATIONS = 10;
  public static final int GENERATION_MIN_PARALLELISM = 1;
  public static final int GENERATION_MAX_PARALLELISM = 4;

  public Argon2idProperties {
    requireInRange(memoryKib, GENERATION_MIN_MEMORY_KIB, GENERATION_MAX_MEMORY_KIB, "memory-kib");
    requireInRange(iterations, GENERATION_MIN_ITERATIONS, GENERATION_MAX_ITERATIONS, "iterations");
    requireInRange(
        parallelism, GENERATION_MIN_PARALLELISM, GENERATION_MAX_PARALLELISM, "parallelism");
  }

  private static void requireInRange(int value, int min, int max, String name) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          "itda.security.password.argon2."
              + name
              + "은(는) "
              + min
              + "~"
              + max
              + " 범위여야 합니다: "
              + value);
    }
  }
}
