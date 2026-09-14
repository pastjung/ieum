package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 계정 열거 방지용 미끼 해시의 성질을 고정한다 (API 명세 3.5절).
 *
 * <p>여기서 확인하는 것은 "고정 비밀이 아니고, 한 번만 만들며, 값이 밖으로 새지 않는다"는 세 가지다.
 */
class DecoyPasswordHashTest {

  @Test
  void 운영과_같은_해시기로_한_번만_만든다() {
    RecordingHasher hasher = new RecordingHasher();

    DecoyPasswordHash decoy = new DecoyPasswordHash(hasher);
    decoy.value();
    decoy.value();

    // 요청마다 다시 만들면 Argon2id 비용을 반복해서 치른다.
    assertThat(hasher.hashedSecrets).hasSize(1);
    assertThat(decoy.value()).isEqualTo(hasher.lastEncoded);
  }

  @Test
  void 비밀을_소스에_적어_두지_않고_매번_새로_만든다() {
    RecordingHasher hasher = new RecordingHasher();

    new DecoyPasswordHash(hasher);
    new DecoyPasswordHash(hasher);

    // 고정 값이면 그 자체가 공유 비밀이 된다.
    assertThat(hasher.hashedSecrets).hasSize(2);
    assertThat(hasher.hashedSecrets.get(0)).isNotEqualTo(hasher.hashedSecrets.get(1));
    assertThat(hasher.hashedSecrets.get(0)).hasSizeGreaterThanOrEqualTo(32);
  }

  @Test
  void toString에_해시가_없다() {
    RecordingHasher hasher = new RecordingHasher();
    DecoyPasswordHash decoy = new DecoyPasswordHash(hasher);

    assertThat(decoy).hasToString("DecoyPasswordHash[REDACTED]");
    assertThat(decoy.toString()).doesNotContain(decoy.value());
  }

  private static final class RecordingHasher implements PasswordHasher {

    private final List<String> hashedSecrets = new ArrayList<>();
    private String lastEncoded;

    @Override
    public PasswordHasher.EncodedPassword hash(String normalizedPassword) {
      this.hashedSecrets.add(normalizedPassword);
      this.lastEncoded =
          "$argon2id$v=19$m=19456,t=2,p=1$fake-salt$fake-hash-" + this.hashedSecrets.size();
      return PasswordHasher.EncodedPassword.of(this.lastEncoded);
    }

    @Override
    public boolean matches(String normalizedPassword, String encodedHash) {
      throw new UnsupportedOperationException("미끼 해시를 만드는 데는 쓰지 않는다.");
    }
  }
}
