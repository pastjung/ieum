package com.itda.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 키 표기는 계약(API 명세 3.6.1절)이 글자 단위로 정한 값이다. 한 글자만 달라도 이미 저장된 상태를 못 찾는다. */
class AuthRedisKeysTest {

  private static final AuthRedisKeys KEYS = new AuthRedisKeys(new AuthRedisProperties("test"));

  @Test
  void 토큰_키는_계약_표기를_따른다() {
    assertThat(KEYS.refreshToken(42L, "jti-1")).isEqualTo("test:auth:{42}:refresh:jti-1");
  }

  @Test
  void 계열_키는_계약_표기를_따른다() {
    assertThat(KEYS.family(42L, "family-1")).isEqualTo("test:auth:{42}:family:family-1");
  }

  @Test
  void 계열_index_키는_계약_표기를_따른다() {
    assertThat(KEYS.families(42L)).isEqualTo("test:auth:{42}:families");
  }

  @Test
  void 한_사용자의_키는_같은_hash_tag를_쓴다() {
    // Cluster로 가더라도 한 연산이 건드리는 키가 같은 slot에 모여야 한다.
    String tag = "{42}";

    assertThat(KEYS.refreshToken(42L, "jti-1")).contains(tag);
    assertThat(KEYS.family(42L, "family-1")).contains(tag);
    assertThat(KEYS.families(42L)).contains(tag);
  }

  @Test
  void BIGINT_상한의_사용자_식별자도_정확히_표기한다() {
    // 정밀도를 잃는 실수 표기로 바뀌면 다른 사용자의 키를 가리키게 된다.
    assertThat(KEYS.families(9_223_372_036_854_775_807L))
        .isEqualTo("test:auth:{9223372036854775807}:families");
  }

  @Test
  void 환경_이름이_바뀌면_키_공간이_분리된다() {
    AuthRedisKeys localKeys = new AuthRedisKeys(new AuthRedisProperties("local"));

    assertThat(localKeys.families(42L)).isEqualTo("local:auth:{42}:families");
    assertThat(localKeys.families(42L)).isNotEqualTo(KEYS.families(42L));
  }
}
