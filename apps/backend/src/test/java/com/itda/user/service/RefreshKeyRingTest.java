package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.support.security.RefreshTokenTestKeys;
import org.junit.jupiter.api.Test;

class RefreshKeyRingTest {

  private static RefreshKeyRing ringOf(String activeKid, String activeKey, String retiredKeys) {
    return RefreshKeyRing.from(new RefreshJwtProperties(activeKid, activeKey, retiredKeys));
  }

  @Test
  void active_key만_있으면_ring에_하나만_담긴다() {
    RefreshKeyRing ring = ringOf("refresh-active", RefreshTokenTestKeys.randomKey(), "");

    assertThat(ring.size()).isEqualTo(1);
    assertThat(ring.activeKid()).isEqualTo("refresh-active");
    assertThat(ring.activeKey()).isNotNull();
    assertThat(ring.kids()).containsExactly("refresh-active");
  }

  @Test
  void retired_key를_kid로_찾을_수_있다() {
    String retired = RefreshTokenTestKeys.randomKey();

    RefreshKeyRing ring =
        ringOf("refresh-active", RefreshTokenTestKeys.randomKey(), "refresh-old:" + retired);

    assertThat(ring.size()).isEqualTo(2);
    assertThat(ring.findByKid("refresh-old")).isPresent();
    assertThat(ring.kids()).containsExactlyInAnyOrder("refresh-active", "refresh-old");
  }

  @Test
  void 여러_retired_key를_쉼표로_이어_붙인다() {
    RefreshKeyRing ring =
        ringOf(
            "refresh-active",
            RefreshTokenTestKeys.randomKey(),
            " refresh-old1:"
                + RefreshTokenTestKeys.randomKey()
                + " , refresh-old2:"
                + RefreshTokenTestKeys.randomKey()
                + " ");

    assertThat(ring.size()).isEqualTo(3);
    assertThat(ring.findByKid("refresh-old1")).isPresent();
    assertThat(ring.findByKid("refresh-old2")).isPresent();
  }

  @Test
  void ring에_없는_kid는_빈_값이다() {
    RefreshKeyRing ring = ringOf("refresh-active", RefreshTokenTestKeys.randomKey(), "");

    assertThat(ring.findByKid("access-active")).isEmpty();
    assertThat(ring.findByKid("")).isEmpty();
  }

  @Test
  void kid가_중복되면_기동에_실패한다() {
    assertThatThrownBy(
            () ->
                ringOf(
                    "refresh-active",
                    RefreshTokenTestKeys.randomKey(),
                    "refresh-active:" + RefreshTokenTestKeys.randomKey()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("중복");
  }

  @Test
  void 형식이_어긋난_retired_항목을_거부한다() {
    String key = RefreshTokenTestKeys.randomKey();

    assertThatThrownBy(() -> ringOf("refresh-active", key, "kid-없이-key만"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ringOf("refresh-active", key, ":" + key))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ringOf("refresh-active", key, "refresh-old:"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void Base64URL이_아닌_key를_거부한다() {
    assertThatThrownBy(() -> ringOf("refresh-active", "not base64!!", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("디코딩");
  }

  @Test
  void 계약_최소_길이보다_짧은_key를_거부한다() {
    assertThatThrownBy(() -> ringOf("refresh-active", RefreshTokenTestKeys.randomKey(31), ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32바이트");
  }

  @Test
  void 실패_message에_key_원문이_없다() {
    String key = RefreshTokenTestKeys.randomKey(31);

    assertThatThrownBy(() -> ringOf("refresh-active", key, "")).hasMessageNotContaining(key);
  }

  @Test
  void toString은_key를_가린다() {
    String key = RefreshTokenTestKeys.randomKey();

    RefreshKeyRing ring = ringOf("refresh-active", key, "");

    assertThat(ring.toString()).isEqualTo("RefreshKeyRing[REDACTED]");
    assertThat(ring.toString()).doesNotContain(key);
  }
}
