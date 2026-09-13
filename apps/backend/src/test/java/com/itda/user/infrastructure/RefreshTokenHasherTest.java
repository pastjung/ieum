package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class RefreshTokenHasherTest {

  private static final String SAMPLE = "test-compact-value";

  @Test
  void 계약이_정한_인코딩으로_해시한다() {
    String hash = RefreshTokenHasher.hash(SAMPLE);

    // SHA-256을 Base64URL(padding 없음)로 — 32바이트가 43자가 된다.
    assertThat(hash).hasSize(43).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    assertThat(Base64.getUrlDecoder().decode(hash)).hasSize(32);
  }

  @Test
  void 같은_값은_같은_해시가_된다() {
    assertThat(RefreshTokenHasher.hash(SAMPLE)).isEqualTo(RefreshTokenHasher.hash(SAMPLE));
  }

  @Test
  void 다른_값은_다른_해시가_된다() {
    assertThat(RefreshTokenHasher.hash(SAMPLE)).isNotEqualTo(RefreshTokenHasher.hash(SAMPLE + "x"));
  }

  @Test
  void 해시에_원문이_남지_않는다() {
    assertThat(RefreshTokenHasher.hash(SAMPLE)).doesNotContain(SAMPLE);
  }

  @Test
  void 빈_값은_거부하고_입력을_message에_넣지_않는다() {
    assertThatThrownBy(() -> RefreshTokenHasher.hash(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RefreshTokenHasher.hash(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("해시할 토큰 값이 없습니다.");
  }
}
