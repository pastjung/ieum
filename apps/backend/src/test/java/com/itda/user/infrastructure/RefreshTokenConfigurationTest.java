package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.support.security.AccessTokenTestKeys;
import com.itda.support.security.RefreshTokenTestKeys;
import org.junit.jupiter.api.Test;

/**
 * {@code kid}는 ring을 통틀어 유일해야 한다 (API 명세 3.6.1절).
 *
 * <p>겹치면 어느 ring의 key로 검증할지 결정할 수 없고, 한 ring의 유출 회전이 다른 ring의 토큰까지 끌고 들어간다. 기동 단계에서 막지 않으면 운영 중에
 * 발견된다.
 */
class RefreshTokenConfigurationTest {

  private final RefreshTokenConfiguration configuration = new RefreshTokenConfiguration();

  private static AccessKeyRing accessRing(String activeKid, String retiredKeys) {
    return AccessKeyRing.from(
        new AccessJwtProperties(
            AccessTokenTestKeys.ISSUER, activeKid, AccessTokenTestKeys.randomKey(), retiredKeys));
  }

  @Test
  void kid가_겹치지_않으면_ring을_만든다() {
    RefreshKeyRing ring =
        this.configuration.refreshKeyRing(
            RefreshTokenTestKeys.properties("refresh-active"), accessRing("access-active", ""));

    assertThat(ring.activeKid()).isEqualTo("refresh-active");
  }

  @Test
  void active_kid가_Access와_겹치면_기동에_실패한다() {
    assertThatThrownBy(
            () ->
                this.configuration.refreshKeyRing(
                    RefreshTokenTestKeys.properties("shared-kid"), accessRing("shared-kid", "")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("kid가 겹칩니다");
  }

  @Test
  void retired_kid가_겹쳐도_기동에_실패한다() {
    RefreshJwtProperties refresh =
        new RefreshJwtProperties(
            "refresh-active",
            RefreshTokenTestKeys.randomKey(),
            "shared-old:" + RefreshTokenTestKeys.randomKey());

    assertThatThrownBy(
            () ->
                this.configuration.refreshKeyRing(
                    refresh,
                    accessRing("access-active", "shared-old:" + AccessTokenTestKeys.randomKey())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 실패_message에_kid나_key가_없다() {
    String refreshKey = RefreshTokenTestKeys.randomKey();

    assertThatThrownBy(
            () ->
                this.configuration.refreshKeyRing(
                    new RefreshJwtProperties("shared-kid", refreshKey, ""),
                    accessRing("shared-kid", "")))
        .hasMessageNotContaining("shared-kid")
        .hasMessageNotContaining(refreshKey);
  }

  @Test
  void codec은_Access와_같은_issuer를_쓴다() {
    AccessJwtProperties accessProperties = AccessTokenTestKeys.properties("access-active");
    RefreshKeyRing ring = RefreshKeyRing.from(RefreshTokenTestKeys.properties("refresh-active"));

    assertThatCode(() -> this.configuration.nimbusRefreshTokenCodec(ring, accessProperties))
        .doesNotThrowAnyException();
  }
}
