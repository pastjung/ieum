package com.itda.user.repository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 이 값들은 그대로 Redis 키 문자열이 된다. 형식을 확인하지 않으면 잘못된 값이 임의의 키를 만들어 내고, 그 키는 다른 사용자의 상태와 구분되지 않는다.
 *
 * <p>실패 message에 값 자체가 들어가지 않는 것도 함께 고정한다.
 */
class RefreshTokenRecordTest {

  private static final String HASH = "0123456789012345678901234567890123456789012";
  private static final long EXPIRES_AT = 4_102_444_800L;

  private static RefreshTokenStore.TokenRecord record(
      String familyId, String jti, String tokenHash) {
    return new RefreshTokenStore.TokenRecord(1L, familyId, jti, tokenHash, EXPIRES_AT);
  }

  private static String uuid() {
    return UUID.randomUUID().toString();
  }

  @Test
  void 계약_형식을_모두_만족하면_만들어진다() {
    assertThatCode(() -> record(uuid(), uuid(), HASH)).doesNotThrowAnyException();
  }

  @Test
  void 해시는_정확히_43자의_Base64URL이어야_한다() {
    assertThatCode(() -> record(uuid(), uuid(), HASH)).doesNotThrowAnyException();
    assertThatCode(() -> record(uuid(), uuid(), "-_" + HASH.substring(2)))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "012345678901234567890123456789012345678901", // 42자
        "01234567890123456789012345678901234567890123", // 44자
        "012345678901234567890123456789012345678901+", // Base64URL이 아닌 문자
        "012345678901234567890123456789012345678901/",
        "01234567890123456789012345678901234567890=="
      })
  void 형식이_다른_해시를_거부한다(String tokenHash) {
    assertThatThrownBy(() -> record(uuid(), uuid(), tokenHash))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-a-uuid",
        "3F2A9C1E-1111-4111-8111-111111111111", // 대문자
        "3f2a9c1e-1111-1111-8111-111111111111", // v4가 아님
        "3f2a9c1e-1111-4111-c111-111111111111", // variant가 아님
        "3f2a9c1e11114111811111111111111",
        "../other-key",
        "3f2a9c1e-1111-4111-8111-111111111111:extra"
      })
  void 형식이_다른_계열_식별자와_토큰_식별자를_거부한다(String value) {
    assertThatThrownBy(() -> record(value, uuid(), HASH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> record(uuid(), value, HASH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 값이_없으면_거부한다() {
    assertThatThrownBy(() -> record(null, uuid(), HASH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> record(uuid(), null, HASH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> record(uuid(), uuid(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 사용자_식별자와_만료_시각의_경계를_확인한다() {
    assertThatThrownBy(
            () -> new RefreshTokenStore.TokenRecord(0L, uuid(), uuid(), HASH, EXPIRES_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RefreshTokenStore.TokenRecord(-1L, uuid(), uuid(), HASH, EXPIRES_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RefreshTokenStore.TokenRecord(1L, uuid(), uuid(), HASH, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> new RefreshTokenStore.TokenRecord(Long.MAX_VALUE, uuid(), uuid(), HASH, 1))
        .doesNotThrowAnyException();
  }

  @Test
  void 검증_메서드는_밖에서도_같은_규칙을_쓴다() {
    String familyId = uuid();

    assertThatCode(() -> RefreshTokenStore.TokenRecord.requireFamilyId(familyId))
        .doesNotThrowAnyException();
    assertThatCode(() -> RefreshTokenStore.TokenRecord.requireJti(uuid()))
        .doesNotThrowAnyException();
    assertThatCode(() -> RefreshTokenStore.TokenRecord.requireTokenHash(HASH))
        .doesNotThrowAnyException();
    assertThatCode(() -> RefreshTokenStore.TokenRecord.requireUserId(1L))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> RefreshTokenStore.TokenRecord.requireFamilyId("../other-key"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 실패_message에_식별자나_해시가_없다() {
    String familyId = uuid();

    assertThatThrownBy(() -> record(familyId.toUpperCase(), uuid(), HASH))
        .hasMessageNotContaining(familyId.toUpperCase());
    assertThatThrownBy(() -> record(familyId, uuid(), HASH + "x")).hasMessageNotContaining(HASH);
  }
}
