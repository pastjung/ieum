package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.global.security.AccessJwtProperties;
import com.itda.global.security.AccessKeyRing;
import com.itda.support.security.AccessTokenTestKeys;
import org.junit.jupiter.api.Test;

/**
 * 세 ring의 {@code kid} 전역 유일성이 기동 단계에서 강제되는지 고정한다.
 *
 * <p>계약(API 명세 3.6.1절 「key rotation」)은 "{@code kid}는 세 ring을 통틀어 유일해야 한다"고 정한다. Access↔Refresh는
 * {@link RefreshTokenConfiguration}이 확인하므로, 나머지 두 쌍(Access↔action, Refresh↔action)을 {@link
 * ActionTokenServiceConfiguration#actionKeyRing}이 맡는다. 이 테스트가 막는 것은 그 두 호출이 사라지는 회귀다 — 겹치지 않는 kid로만
 * 기동하는 다른 테스트들은 호출이 없어져도 전부 통과한다.
 *
 * <p>{@code @SpringBootTest}를 쓰지 않는다. 검증 대상은 컨텍스트 조립이 아니라 configuration 메서드 하나의 판정이고, 충돌 조합을 여러 번
 * 바꿔 넣으려면 컨텍스트를 매번 다시 띄우는 것보다 직접 호출하는 편이 정확하다.
 *
 * <p>ring 안쪽 검증({@link ActionKeyRing}의 길이·형식·중복, {@link ActionJwtProperties}의 누락·placeholder)은 이
 * 클래스의 범위가 아니다.
 */
class ActionTokenServiceConfigurationTest {

  private static final String ACCESS_KID = "access-active-1";
  private static final String ACCESS_RETIRED_KID = "access-retired-1";
  private static final String REFRESH_KID = "refresh-active-1";
  private static final String REFRESH_RETIRED_KID = "refresh-retired-1";
  private static final String ACTION_KID = "action-active-1";
  private static final String ACTION_RETIRED_KID = "action-retired-1";

  private final ActionTokenServiceConfiguration configuration =
      new ActionTokenServiceConfiguration();

  /** {@code kid:base64url} 한 항목. key 값은 매번 새로 만들어 저장소에 고정 문자열을 남기지 않는다. */
  private static String retiredEntry(String kid) {
    return kid + ":" + AccessTokenTestKeys.randomKey();
  }

  private static AccessKeyRing accessRing(String activeKid, String retiredKeys) {
    return AccessKeyRing.from(
        new AccessJwtProperties(
            AccessTokenTestKeys.ISSUER, activeKid, AccessTokenTestKeys.randomKey(), retiredKeys));
  }

  private static RefreshKeyRing refreshRing(String activeKid, String retiredKeys) {
    return RefreshKeyRing.from(
        new RefreshJwtProperties(activeKid, RefreshTokenTestKeys.randomKey(), retiredKeys));
  }

  private static ActionJwtProperties actionProperties(String activeKid, String retiredKeys) {
    return new ActionJwtProperties(activeKid, ActionTokenTestKeys.randomKey(), retiredKeys);
  }

  private ActionKeyRing build(
      ActionJwtProperties action, AccessKeyRing access, RefreshKeyRing refresh) {
    return this.configuration.actionKeyRing(action, access, refresh);
  }

  @Test
  void 세_ring의_kid가_모두_다르면_action_ring을_만든다() {
    ActionKeyRing ring =
        build(
            actionProperties(ACTION_KID, retiredEntry(ACTION_RETIRED_KID)),
            accessRing(ACCESS_KID, retiredEntry(ACCESS_RETIRED_KID)),
            refreshRing(REFRESH_KID, retiredEntry(REFRESH_RETIRED_KID)));

    assertThat(ring.activeKid()).isEqualTo(ACTION_KID);
    assertThat(ring.kids()).containsExactlyInAnyOrder(ACTION_KID, ACTION_RETIRED_KID);
  }

  @Test
  void retired가_없어도_만들어진다() {
    assertThatCode(
            () ->
                build(
                    actionProperties(ACTION_KID, ""),
                    accessRing(ACCESS_KID, ""),
                    refreshRing(REFRESH_KID, "")))
        .doesNotThrowAnyException();
  }

  // ---------- Access ↔ action ----------

  @Test
  void action_active_kid가_Access_active_kid와_겹치면_기동에_실패한다() {
    assertThatThrownBy(
            () ->
                build(
                    actionProperties(ACCESS_KID, ""),
                    accessRing(ACCESS_KID, ""),
                    refreshRing(REFRESH_KID, "")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void action_active_kid가_Access_retired_kid와_겹치면_기동에_실패한다() {
    assertThatThrownBy(
            () ->
                build(
                    actionProperties(ACCESS_RETIRED_KID, ""),
                    accessRing(ACCESS_KID, retiredEntry(ACCESS_RETIRED_KID)),
                    refreshRing(REFRESH_KID, "")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void action_retired_kid가_Access_active_kid와_겹치면_기동에_실패한다() {
    assertThatThrownBy(
            () ->
                build(
                    actionProperties(ACTION_KID, retiredEntry(ACCESS_KID)),
                    accessRing(ACCESS_KID, ""),
                    refreshRing(REFRESH_KID, "")))
        .isInstanceOf(IllegalStateException.class);
  }

  // ---------- Refresh ↔ action ----------

  @Test
  void action_active_kid가_Refresh_active_kid와_겹치면_기동에_실패한다() {
    assertThatThrownBy(
            () ->
                build(
                    actionProperties(REFRESH_KID, ""),
                    accessRing(ACCESS_KID, ""),
                    refreshRing(REFRESH_KID, "")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void action_active_kid가_Refresh_retired_kid와_겹치면_기동에_실패한다() {
    assertThatThrownBy(
            () ->
                build(
                    actionProperties(REFRESH_RETIRED_KID, ""),
                    accessRing(ACCESS_KID, ""),
                    refreshRing(REFRESH_KID, retiredEntry(REFRESH_RETIRED_KID))))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void action_retired_kid가_Refresh_active_kid와_겹치면_기동에_실패한다() {
    assertThatThrownBy(
            () ->
                build(
                    actionProperties(ACTION_KID, retiredEntry(REFRESH_KID)),
                    accessRing(ACCESS_KID, ""),
                    refreshRing(REFRESH_KID, "")))
        .isInstanceOf(IllegalStateException.class);
  }

  // ---------- 실패 message ----------

  @Test
  void 충돌_message에_kid도_key도_남기지_않는다() {
    String accessKey = AccessTokenTestKeys.randomKey();
    String refreshKey = RefreshTokenTestKeys.randomKey();
    String actionKey = ActionTokenTestKeys.randomKey();

    assertThatThrownBy(
            () ->
                build(
                    new ActionJwtProperties(ACCESS_KID, actionKey, ""),
                    AccessKeyRing.from(
                        new AccessJwtProperties(
                            AccessTokenTestKeys.ISSUER, ACCESS_KID, accessKey, "")),
                    RefreshKeyRing.from(new RefreshJwtProperties(REFRESH_KID, refreshKey, ""))))
        .isInstanceOf(IllegalStateException.class)
        // kid는 비밀값이 아니지만 어느 kid가 겹쳤는지까지 알려 줄 이유가 없고, key는 절대 새면 안 된다.
        .hasMessageNotContaining(ACCESS_KID)
        .hasMessageNotContaining(accessKey)
        .hasMessageNotContaining(refreshKey)
        .hasMessageNotContaining(actionKey);
  }
}
