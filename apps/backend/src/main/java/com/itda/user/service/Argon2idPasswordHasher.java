package com.itda.user.service;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Argon2id 해시 어댑터. 생성은 Spring Security의 {@link Argon2PasswordEncoder}(내부 구현은 Bouncy Castle)에 위임하고,
 * 검증 전에 저장된 인코딩 문자열을 직접 검사한다.
 *
 * <p>기본 factory {@code defaultsForSpringSecurity_v5_8()}를 쓰지 않는 이유는 그 memory가 16MiB로 계약 최소값 19MiB에
 * 미달하기 때문이다.
 *
 * <p><b>저장된 해시를 신뢰하지 않는 이유.</b> {@code Argon2PasswordEncoder.matches}는 저장된 문자열에 적힌 m·t·p로 Argon2를
 * 초기화한다. 즉 어떤 경위로든 {@code m=4194304}가 기록된 행 하나면 검증 요청마다 4GiB 할당을 시도하게 된다. Spring은 이 값을 제한하지 않고 형식
 * 파서도 package-private이라 재사용할 수 없으므로, 여기서 형식과 파라미터 범위를 먼저 확인하고 범위를 벗어나면 Argon2를 실행하지 않는다.
 */
public class Argon2idPasswordHasher implements PasswordHasher {

  /** ERD 3.1절 {@code users.password_hash VARCHAR(255)}. */
  static final int MAX_ENCODED_LENGTH = 255;

  static final int SALT_LENGTH_BYTES = 16;
  static final int HASH_LENGTH_BYTES = 32;

  // 아래 상수는 "이미 저장된 해시를 안전하게 검증할 수 있는 범위"다.
  // 새 해시를 만들 때 허용하는 비용 범위(Argon2idProperties)와 지금은 값이 같지만 뜻이 다르므로 상수를 공유하지 않는다.
  //
  // 생성 설정의 기본값을 나중에 높이더라도 이 검증 하한은 따라 올라가지 않는다. 함께 올리면 기존 비용으로 저장된
  // 해시가 그 순간 전부 검증 불가가 되어 아무도 로그인하지 못하고, 로그인해야 가능한 재해시 경로도 함께 막힌다.
  // 향후 재해시는 기존의 안전한 해시를 먼저 검증해 본인임을 확인한 뒤 새 비용으로 교체하는 순서여야 한다.
  // 이번 Issue에서는 needsRehash 판정과 DB 갱신을 구현하지 않는다.
  //
  // 상한은 성격이 다르다. 저장된 값이 시키는 대로 자원을 잡지 않기 위한 방어선이므로 생성 범위와 별개로 유지한다.
  static final int VERIFIABLE_MIN_MEMORY_KIB = 19456;
  static final int VERIFIABLE_MAX_MEMORY_KIB = 262144;
  static final int VERIFIABLE_MIN_ITERATIONS = 2;
  static final int VERIFIABLE_MAX_ITERATIONS = 10;
  static final int VERIFIABLE_MIN_PARALLELISM = 1;
  static final int VERIFIABLE_MAX_PARALLELISM = 4;

  /**
   * 허용하는 인코딩 형식. argon2id·v=19만 받고, 필드 순서·개수·구분자를 전부 고정해 후행 데이터나 예상하지 않은 필드를 거른다. Base64는 우리 인코더와
   * 같이 padding이 없다.
   */
  private static final Pattern ENCODED_PATTERN =
      Pattern.compile(
          "^\\$argon2id\\$v=19\\$m=([1-9]\\d{0,6}),t=([1-9]\\d?),p=([1-9])"
              + "\\$([A-Za-z0-9+/]{1,128})\\$([A-Za-z0-9+/]{1,128})$");

  private static final Logger log = LoggerFactory.getLogger(Argon2idPasswordHasher.class);

  private final Argon2PasswordEncoder encoder;

  public Argon2idPasswordHasher(Argon2idProperties properties) {
    this.encoder =
        new Argon2PasswordEncoder(
            SALT_LENGTH_BYTES,
            HASH_LENGTH_BYTES,
            properties.parallelism(),
            properties.memoryKib(),
            properties.iterations());
  }

  /**
   * 빈 값은 해시하지 않는다. 길이 정책이 이미 거부하는 값이고, Spring Security의 인코더는 빈 입력으로 만든 해시를 이후 어떤 입력과도 일치시키지 않아 검증
   * 불가능한 해시만 저장소에 남는다. 공백만으로 이루어진 값은 거부하지 않는다 — 비밀번호는 trim하지 않으며 공백도 값의 일부다.
   */
  @Override
  public PasswordHasher.EncodedPassword hash(String normalizedPassword) {
    if (normalizedPassword == null || normalizedPassword.isEmpty()) {
      // 입력값을 message에 넣지 않는다.
      throw new IllegalArgumentException("해시할 비밀번호 값이 없습니다.");
    }
    return PasswordHasher.EncodedPassword.of(this.encoder.encode(normalizedPassword));
  }

  @Override
  public boolean matches(String normalizedPassword, String encodedHash) {
    if (!isSupportedEncoding(encodedHash)) {
      // 저장된 값·예외 message를 남기지 않는다. 해시 조각도 공격자에게는 단서다.
      log.warn("저장된 비밀번호 해시의 형식 또는 파라미터가 허용 범위를 벗어나 검증하지 않았습니다.");
      return false;
    }
    return this.encoder.matches(normalizedPassword, encodedHash);
  }

  /** 형식·알고리즘·버전·파라미터 범위·salt/hash 길이를 모두 만족할 때만 Argon2를 실행한다. */
  private static boolean isSupportedEncoding(String encodedHash) {
    if (encodedHash == null || encodedHash.length() > MAX_ENCODED_LENGTH) {
      return false;
    }
    Matcher matcher = ENCODED_PATTERN.matcher(encodedHash);
    if (!matcher.matches()) {
      return false;
    }
    if (!hasSafeCostParameters(matcher)) {
      return false;
    }
    return hasExpectedLength(matcher.group(4), SALT_LENGTH_BYTES)
        && hasExpectedLength(matcher.group(5), HASH_LENGTH_BYTES);
  }

  /** 현재 인스턴스의 생성 설정이 아니라 저장 해시 검증 범위로 판정한다. 그래야 생성 비용을 올려도 기존 해시를 계속 검증할 수 있다. */
  private static boolean hasSafeCostParameters(Matcher matcher) {
    int memoryKib = Integer.parseInt(matcher.group(1));
    int iterations = Integer.parseInt(matcher.group(2));
    int parallelism = Integer.parseInt(matcher.group(3));
    return isInRange(memoryKib, VERIFIABLE_MIN_MEMORY_KIB, VERIFIABLE_MAX_MEMORY_KIB)
        && isInRange(iterations, VERIFIABLE_MIN_ITERATIONS, VERIFIABLE_MAX_ITERATIONS)
        && isInRange(parallelism, VERIFIABLE_MIN_PARALLELISM, VERIFIABLE_MAX_PARALLELISM);
  }

  private static boolean isInRange(int value, int min, int max) {
    return value >= min && value <= max;
  }

  private static boolean hasExpectedLength(String base64Value, int expectedBytes) {
    try {
      return Base64.getDecoder().decode(base64Value).length == expectedBytes;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }
}
