package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.itda.user.application.port.out.EncodedPassword;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Argon2idPasswordHasherTest {

  private static final String PASSWORD = "충분히 긴 새 비밀번호 문구";
  private static final Base64.Encoder BASE64 = Base64.getEncoder().withoutPadding();

  private Argon2idPasswordHasher passwordHasher;

  @BeforeEach
  void setUp() {
    this.passwordHasher = new Argon2idPasswordHasher(new Argon2idProperties(19456, 2, 1));
  }

  private String validEncodedHash() {
    return this.passwordHasher.hash(PASSWORD).value();
  }

  private static String encodedWith(String saltBase64, String hashBase64) {
    return "$argon2id$v=19$m=19456,t=2,p=1$" + saltBase64 + "$" + hashBase64;
  }

  @Test
  void 같은_비밀번호를_검증하면_일치한다() {
    String encoded = validEncodedHash();

    assertThat(this.passwordHasher.matches(PASSWORD, encoded)).isTrue();
  }

  @Test
  void 다른_비밀번호는_일치하지_않는다() {
    String encoded = validEncodedHash();

    assertThat(this.passwordHasher.matches(PASSWORD + "x", encoded)).isFalse();
  }

  @Test
  void 같은_비밀번호도_salt가_달라_매번_다른_해시가_되고_둘_다_검증에_성공한다() {
    EncodedPassword first = this.passwordHasher.hash(PASSWORD);
    EncodedPassword second = this.passwordHasher.hash(PASSWORD);

    assertThat(first.value()).isNotEqualTo(second.value());
    assertThat(this.passwordHasher.matches(PASSWORD, first.value())).isTrue();
    assertThat(this.passwordHasher.matches(PASSWORD, second.value())).isTrue();
  }

  @Test
  void 인코딩_결과는_계약된_argon2id_PHC_형식이다() {
    String encoded = validEncodedHash();

    assertThat(encoded)
        .matches("^\\$argon2id\\$v=19\\$m=19456,t=2,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}$");
  }

  @Test
  void 인코딩_결과는_password_hash_컬럼_길이_255_이하다() {
    String encoded = validEncodedHash();

    assertThat(encoded.length()).isEqualTo(97).isLessThanOrEqualTo(255);
  }

  @Test
  void 설정한_비용_파라미터가_인코딩_문자열에_그대로_들어간다() {
    Argon2idPasswordHasher strongerHasher =
        new Argon2idPasswordHasher(new Argon2idProperties(32768, 3, 2));

    String encoded = strongerHasher.hash(PASSWORD).value();

    assertThat(encoded).startsWith("$argon2id$v=19$m=32768,t=3,p=2$");
    assertThat(strongerHasher.matches(PASSWORD, encoded)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "   ",
        "not-a-hash",
        "$argon2id$v=19$m=19456,t=2,p=1$",
        "$argon2id$v=19$m=19456,t=2,p=1$onlysalt",
        "$argon2id$m=19456,t=2,p=1$c2FsdHNhbHRzYWx0c2Fs$aGFzaA",
        "$argon2id$v=19$m=19456,t=2$c2FsdHNhbHRzYWx0c2Fs$aGFzaA",
        "$argon2id$v=19$t=2,p=1,m=19456$c2FsdHNhbHRzYWx0c2Fs$aGFzaA"
      })
  void 손상된_인코딩은_예외_없이_false다(String malformed) {
    assertThat(this.passwordHasher.matches(PASSWORD, malformed)).isFalse();
  }

  @Test
  void null_해시는_예외_없이_false다() {
    assertThat(this.passwordHasher.matches(PASSWORD, null)).isFalse();
  }

  @Test
  void 후행_데이터가_붙은_인코딩은_false다() {
    assertThat(this.passwordHasher.matches(PASSWORD, validEncodedHash() + "$extra")).isFalse();
    assertThat(this.passwordHasher.matches(PASSWORD, validEncodedHash() + " ")).isFalse();
  }

  @Test
  void 길이가_255를_넘는_문자열은_false다() {
    String tooLong = validEncodedHash() + "A".repeat(255);

    assertThat(this.passwordHasher.matches(PASSWORD, tooLong)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "$argon2i$v=19$m=19456,t=2,p=1$c2FsdHNhbHRzYWx0c2FsdA$aGFzaA",
        "$argon2d$v=19$m=19456,t=2,p=1$c2FsdHNhbHRzYWx0c2FsdA$aGFzaA",
        "$argon2$v=19$m=19456,t=2,p=1$c2FsdHNhbHRzYWx0c2FsdA$aGFzaA",
        "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
      })
  void 지원하지_않는_알고리즘은_false다(String unsupported) {
    assertThat(this.passwordHasher.matches(PASSWORD, unsupported)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"v=16", "v=13", "v=190"})
  void 지원하지_않는_Argon2_version은_false다(String version) {
    String encoded = validEncodedHash().replace("v=19", version);

    assertThat(this.passwordHasher.matches(PASSWORD, encoded)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "m=262145,t=2,p=1", // 메모리 상한 초과
        "m=9999999,t=2,p=1", // 메모리 상한 크게 초과
        "m=19456,t=11,p=1", // 반복 상한 초과
        "m=19456,t=2,p=5", // 병렬도 상한 초과
        "m=19455,t=2,p=1", // 계약 최소 미달
        "m=19456,t=1,p=1" // 계약 최소 미달
      })
  void 안전_범위를_벗어난_비용_파라미터는_Argon2를_실행하지_않고_false다(String costParameters) {
    String encoded = validEncodedHash().replace("m=19456,t=2,p=1", costParameters);

    // 가드가 없으면 저장된 값대로 메모리를 잡으려다 멈추거나 죽는다. 즉시 반환하는지까지 확인한다.
    assertTimeout(
        Duration.ofSeconds(2),
        () -> assertThat(this.passwordHasher.matches(PASSWORD, encoded)).isFalse());
  }

  @Test
  void salt_길이가_16바이트가_아니면_false다() {
    String shortSalt = BASE64.encodeToString(new byte[15]);
    String longSalt = BASE64.encodeToString(new byte[17]);
    String hash = BASE64.encodeToString(new byte[32]);

    assertThat(this.passwordHasher.matches(PASSWORD, encodedWith(shortSalt, hash))).isFalse();
    assertThat(this.passwordHasher.matches(PASSWORD, encodedWith(longSalt, hash))).isFalse();
  }

  @Test
  void hash_길이가_32바이트가_아니면_false다() {
    String salt = BASE64.encodeToString(new byte[16]);

    assertThat(
            this.passwordHasher.matches(
                PASSWORD, encodedWith(salt, BASE64.encodeToString(new byte[31]))))
        .isFalse();
    assertThat(
            this.passwordHasher.matches(
                PASSWORD, encodedWith(salt, BASE64.encodeToString(new byte[33]))))
        .isFalse();
  }

  @Test
  void Base64가_아닌_salt나_hash는_false다() {
    String salt = BASE64.encodeToString(new byte[16]);
    String hash = BASE64.encodeToString(new byte[32]);

    assertThat(this.passwordHasher.matches(PASSWORD, encodedWith("!!!!", hash))).isFalse();
    assertThat(this.passwordHasher.matches(PASSWORD, encodedWith(salt, "@@@@"))).isFalse();
    // 우리 인코더는 padding을 쓰지 않는다. padding이 붙은 값은 우리가 만든 해시가 아니다.
    assertThat(
            this.passwordHasher.matches(
                PASSWORD, encodedWith(salt, Base64.getEncoder().encodeToString(new byte[32]))))
        .isFalse();
  }

  @Test
  void null_비밀번호는_해시하지_않는다() {
    assertThatThrownBy(() -> this.passwordHasher.hash(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("해시할 비밀번호 값이 없습니다.");
  }

  @Test
  void 빈_비밀번호는_해시하지_않는다() {
    // 검증에 성공할 수 없는 해시를 저장소에 남기지 않는다.
    assertThatThrownBy(() -> this.passwordHasher.hash(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("해시할 비밀번호 값이 없습니다.");
  }

  @Test
  void 해시_거부_예외_message에는_입력값도_해시도_없다() {
    String secret = "노출되면 안 되는 값";

    assertThatThrownBy(() -> this.passwordHasher.hash(""))
        .hasMessage("해시할 비밀번호 값이 없습니다.")
        .hasMessageNotContaining(secret)
        .hasMessageNotContaining("argon2id");
  }

  @Test
  void 공백만으로_이루어진_비밀번호는_거부하지_않는다() {
    // 비밀번호는 trim하지 않는다. 길이를 채운 공백 문자열은 상위 정책을 통과할 수 있는 정상 입력이다.
    String onlySpaces = " ".repeat(15);

    String encoded = this.passwordHasher.hash(onlySpaces).value();

    assertThat(encoded).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    assertThat(this.passwordHasher.matches(onlySpaces, encoded)).isTrue();
    assertThat(this.passwordHasher.matches(" ".repeat(14), encoded)).isFalse();
  }

  @Test
  void 정상_해시에_빈_비밀번호를_검증하면_false다() {
    String encoded = validEncodedHash();

    assertThat(this.passwordHasher.matches("", encoded)).isFalse();
  }

  @Test
  void 생성_비용을_올려도_기존_최소_비용_해시는_계속_검증한다() {
    // 저장 해시 검증 범위는 생성 설정과 분리돼 있다. 함께 움직이면 비용 상향 순간 기존 사용자가 로그인하지 못하고,
    // 로그인해야 가능한 재해시 경로까지 막힌다.
    String existingHash = this.passwordHasher.hash(PASSWORD).value();
    Argon2idPasswordHasher strongerHasher =
        new Argon2idPasswordHasher(new Argon2idProperties(65536, 4, 2));

    assertThat(existingHash).contains("m=19456,t=2,p=1");
    assertThat(strongerHasher.matches(PASSWORD, existingHash)).isTrue();
  }
}
