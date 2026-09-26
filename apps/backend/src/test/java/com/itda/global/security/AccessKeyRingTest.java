package com.itda.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.support.security.AccessTokenTestKeys;
import org.junit.jupiter.api.Test;

class AccessKeyRingTest {

  private static AccessJwtProperties properties(
      String activeKid, String activeKey, String retired) {
    return new AccessJwtProperties(AccessTokenTestKeys.ISSUER, activeKid, activeKey, retired);
  }

  @Test
  void active_key만으로_ring을_만든다() {
    AccessKeyRing ring =
        AccessKeyRing.from(properties("active-1", AccessTokenTestKeys.randomKey(), ""));

    assertThat(ring.activeKid()).isEqualTo("active-1");
    assertThat(ring.activeKey()).isNotNull();
    assertThat(ring.findByKid("active-1")).isPresent();
    assertThat(ring.size()).isEqualTo(1);
  }

  @Test
  void retired_key도_검증용으로_조회된다() {
    String retired = "retired-1:" + AccessTokenTestKeys.randomKey();

    AccessKeyRing ring =
        AccessKeyRing.from(properties("active-1", AccessTokenTestKeys.randomKey(), retired));

    assertThat(ring.findByKid("retired-1")).isPresent();
    assertThat(ring.activeKid()).isEqualTo("active-1");
    assertThat(ring.size()).isEqualTo(2);
  }

  @Test
  void 여러_retired_key를_쉼표로_구분한다() {
    String retired =
        "retired-1:"
            + AccessTokenTestKeys.randomKey()
            + ",retired-2:"
            + AccessTokenTestKeys.randomKey();

    AccessKeyRing ring =
        AccessKeyRing.from(properties("active-1", AccessTokenTestKeys.randomKey(), retired));

    assertThat(ring.findByKid("retired-1")).isPresent();
    assertThat(ring.findByKid("retired-2")).isPresent();
    assertThat(ring.size()).isEqualTo(3);
  }

  @Test
  void ring에_없는_kid는_빈_값이다() {
    AccessKeyRing ring =
        AccessKeyRing.from(properties("active-1", AccessTokenTestKeys.randomKey(), ""));

    assertThat(ring.findByKid("unknown")).isEmpty();
  }

  @Test
  void 빈_retired_목록과_공백_항목을_허용한다() {
    assertThat(
            AccessKeyRing.from(properties("active-1", AccessTokenTestKeys.randomKey(), " , "))
                .size())
        .isEqualTo(1);
  }

  @Test
  void active와_retired의_kid가_겹치면_거부한다() {
    String retired = "active-1:" + AccessTokenTestKeys.randomKey();

    assertThatThrownBy(
            () ->
                AccessKeyRing.from(
                    properties("active-1", AccessTokenTestKeys.randomKey(), retired)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("중복");
  }

  @Test
  void retired끼리_kid가_겹쳐도_거부한다() {
    String retired =
        "retired-1:"
            + AccessTokenTestKeys.randomKey()
            + ",retired-1:"
            + AccessTokenTestKeys.randomKey();

    assertThatThrownBy(
            () ->
                AccessKeyRing.from(
                    properties("active-1", AccessTokenTestKeys.randomKey(), retired)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("중복");
  }

  @Test
  void Base64URL이_아닌_key를_거부한다() {
    assertThatThrownBy(() -> AccessKeyRing.from(properties("active-1", "not base64!!", "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Base64URL");
  }

  @Test
  void key가_32바이트_미만이면_거부한다() {
    String shortKey = AccessTokenTestKeys.randomKey(31);

    assertThatThrownBy(() -> AccessKeyRing.from(properties("active-1", shortKey, "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32");
    assertThat(AccessKeyRing.from(properties("active-1", AccessTokenTestKeys.randomKey(32), "")))
        .isNotNull();
  }

  @Test
  void retired_항목_형식이_어긋나면_거부한다() {
    assertThatThrownBy(
            () ->
                AccessKeyRing.from(
                    properties("active-1", AccessTokenTestKeys.randomKey(), "noseparator")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccessKeyRing.from(
                    properties("active-1", AccessTokenTestKeys.randomKey(), ":onlykey")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccessKeyRing.from(
                    properties("active-1", AccessTokenTestKeys.randomKey(), "retired-1:")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 예외_message와_toString에_key_값이_없다() {
    String key = AccessTokenTestKeys.randomKey();
    String shortKey = AccessTokenTestKeys.randomKey(16);

    assertThatThrownBy(() -> AccessKeyRing.from(properties("active-1", shortKey, "")))
        .hasMessageNotContaining(shortKey);
    assertThat(AccessKeyRing.from(properties("active-1", key, "")).toString())
        .isEqualTo("AccessKeyRing[REDACTED]")
        .doesNotContain(key);
  }
}
