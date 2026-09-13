package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.redis.RequiresRealRedis;
import com.itda.user.application.port.out.RefreshTokenLookupResult;
import com.itda.user.application.port.out.RefreshTokenRecord;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 사전 조회가 저장된 상태를 어떻게 읽는지 고정한다.
 *
 * <p>조회는 원자적이지 않으므로 정확성의 최종 근거가 아니다. 그래도 결과를 정확히 구분해야 하는 이유는, 어긋난 상태를 {@code ACTIVE}로 읽으면 손상되거나 조작된
 * 기록이 정상 세션으로 인정되기 때문이다.
 */
@SpringBootTest
@RequiresRealRedis
class LuaRefreshTokenLookupRedisTest extends RefreshTokenRedisTestBase {

  private static String someHash() {
    return RefreshTokenHasher.hash("header.payload." + UUID.randomUUID());
  }

  private RefreshTokenLookupResult lookup(String jti, String tokenHash) {
    return this.store.lookup(record(jti, tokenHash));
  }

  private void putTokenField(String jti, String field, String value) {
    this.redis.opsForHash().put(this.keys.refreshToken(this.userId, jti), field, value);
  }

  private void putFamilyField(String field, String value) {
    this.redis.opsForHash().put(this.keys.family(this.userId, this.familyId), field, value);
  }

  @Test
  void 방금_등록한_토큰은_ACTIVE다() {
    String jti = newJti();
    String tokenHash = someHash();
    this.store.issueFamily(record(jti, tokenHash));

    assertThat(lookup(jti, tokenHash)).isEqualTo(RefreshTokenLookupResult.ACTIVE);
  }

  @Test
  void 없는_토큰은_NOT_FOUND다() {
    assertThat(lookup(newJti(), someHash())).isEqualTo(RefreshTokenLookupResult.NOT_FOUND);
  }

  @Test
  void 회전된_토큰은_ROTATED다() {
    String currentJti = newJti();
    RefreshTokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    this.store.rotate(current, record(newJti(), someHash()));

    assertThat(lookup(currentJti, current.tokenHash())).isEqualTo(RefreshTokenLookupResult.ROTATED);
  }

  @Test
  void 토큰_상태가_REVOKED면_REVOKED로_읽는다() {
    // 이번 범위의 운영 규칙은 토큰 단위 REVOKED를 만들지 않는다. 그래도 값이 들어왔을 때
    // 조용히 ACTIVE로 읽히지 않는 것은 확인해야 한다.
    String jti = newJti();
    String tokenHash = someHash();
    this.store.issueFamily(record(jti, tokenHash));
    putTokenField(jti, "status", "REVOKED");

    assertThat(lookup(jti, tokenHash)).isEqualTo(RefreshTokenLookupResult.REVOKED);
  }

  @Test
  void 계열이_폐기되면_FAMILY_REVOKED다() {
    String jti = newJti();
    String tokenHash = someHash();
    this.store.issueFamily(record(jti, tokenHash));
    this.store.revokeFamily(this.userId, this.familyId);

    assertThat(lookup(jti, tokenHash)).isEqualTo(RefreshTokenLookupResult.FAMILY_REVOKED);
  }

  @Test
  void 해시가_다르면_HASH_MISMATCH다() {
    String jti = newJti();
    this.store.issueFamily(record(jti, someHash()));

    assertThat(lookup(jti, someHash())).isEqualTo(RefreshTokenLookupResult.HASH_MISMATCH);
  }

  @Test
  void 저장된_사용자가_다르면_MISMATCHED다() {
    String jti = newJti();
    String tokenHash = someHash();
    this.store.issueFamily(record(jti, tokenHash));
    putTokenField(jti, "userId", Long.toString(this.userId + 1));

    assertThat(lookup(jti, tokenHash)).isEqualTo(RefreshTokenLookupResult.MISMATCHED);
  }

  @Test
  void 저장된_계열이_다르면_MISMATCHED다() {
    String jti = newJti();
    String tokenHash = someHash();
    this.store.issueFamily(record(jti, tokenHash));
    putTokenField(jti, "familyId", UUID.randomUUID().toString());

    assertThat(lookup(jti, tokenHash)).isEqualTo(RefreshTokenLookupResult.MISMATCHED);
  }

  @Test
  void 목록에_없는_상태값은_MISMATCHED다() {
    String jti = newJti();
    String tokenHash = someHash();
    this.store.issueFamily(record(jti, tokenHash));
    putTokenField(jti, "status", "SOMETHING_ELSE");

    assertThat(lookup(jti, tokenHash)).isEqualTo(RefreshTokenLookupResult.MISMATCHED);
  }

  @Test
  void 계열의_현재_토큰이_아니면_MISMATCHED다() {
    String jti = newJti();
    String tokenHash = someHash();
    this.store.issueFamily(record(jti, tokenHash));
    putFamilyField("currentJti", UUID.randomUUID().toString());

    assertThat(lookup(jti, tokenHash)).isEqualTo(RefreshTokenLookupResult.MISMATCHED);
  }

  @Test
  void 토큰만_있고_계열이_없으면_MISMATCHED다() {
    String jti = newJti();
    String tokenHash = someHash();
    this.store.issueFamily(record(jti, tokenHash));
    this.redis.delete(this.keys.family(this.userId, this.familyId));

    assertThat(lookup(jti, tokenHash)).isEqualTo(RefreshTokenLookupResult.MISMATCHED);
  }

  @Test
  void 저장된_만료_시각이_제출한_값과_다르면_MISMATCHED다() {
    String jti = newJti();
    String tokenHash = someHash();
    this.store.issueFamily(record(jti, tokenHash));

    assertThat(this.store.lookup(recordAt(jti, tokenHash, this.expiresAt - 1)))
        .isEqualTo(RefreshTokenLookupResult.MISMATCHED);
  }

  @Test
  void 만료_시각_field가_없으면_MISMATCHED다() {
    String jti = newJti();
    String tokenHash = someHash();
    this.store.issueFamily(record(jti, tokenHash));
    this.redis.opsForHash().delete(this.keys.refreshToken(this.userId, jti), "expiresAt");

    assertThat(lookup(jti, tokenHash)).isEqualTo(RefreshTokenLookupResult.MISMATCHED);
  }

  @Test
  void 만료_시각이_정수_문자열이_아니면_MISMATCHED다() {
    String jti = newJti();
    String tokenHash = someHash();
    this.store.issueFamily(record(jti, tokenHash));
    putTokenField(jti, "expiresAt", this.expiresAt + ".0");

    assertThat(lookup(jti, tokenHash)).isEqualTo(RefreshTokenLookupResult.MISMATCHED);
  }

  @Test
  void 필수_field가_빠지면_MISMATCHED다() {
    String jti = newJti();
    String tokenHash = someHash();
    this.store.issueFamily(record(jti, tokenHash));
    this.redis.opsForHash().delete(this.keys.refreshToken(this.userId, jti), "familyId");

    assertThat(lookup(jti, tokenHash)).isEqualTo(RefreshTokenLookupResult.MISMATCHED);
  }

  // ---------- 재사용 상태는 만료 손상에 가리지 않는다 ----------

  /** 회전이 끝난 뒤 ROTATED로 남은 옛 토큰의 제출용 기록을 돌려준다. */
  private RefreshTokenRecord rotatedToken() {
    String currentJti = newJti();
    RefreshTokenRecord current = record(currentJti, someHash());
    this.store.issueFamily(current);
    this.store.rotate(current, record(newJti(), someHash()));
    return current;
  }

  /** 토큰 단위 REVOKED를 만드는 운영 규칙은 아직 없다. 값이 들어온 상태만 직접 만든다. */
  private RefreshTokenRecord revokedToken() {
    String jti = newJti();
    RefreshTokenRecord token = record(jti, someHash());
    this.store.issueFamily(token);
    putTokenField(jti, "status", "REVOKED");
    return token;
  }

  private void deleteExpiresAt(String jti) {
    this.redis.opsForHash().delete(this.keys.refreshToken(this.userId, jti), "expiresAt");
  }

  @Test
  void 만료_field가_없는_ROTATED_토큰도_ROTATED로_읽는다() {
    RefreshTokenRecord rotated = rotatedToken();
    deleteExpiresAt(rotated.jti());

    assertThat(this.store.lookup(rotated)).isEqualTo(RefreshTokenLookupResult.ROTATED);
  }

  @Test
  void 만료가_손상된_ROTATED_토큰도_ROTATED로_읽는다() {
    RefreshTokenRecord rotated = rotatedToken();
    putTokenField(rotated.jti(), "expiresAt", "corrupted");

    assertThat(this.store.lookup(rotated)).isEqualTo(RefreshTokenLookupResult.ROTATED);
  }

  @Test
  void 만료가_손상되거나_없는_REVOKED_토큰도_REVOKED로_읽는다() {
    RefreshTokenRecord revoked = revokedToken();
    putTokenField(revoked.jti(), "expiresAt", Long.toString(this.expiresAt - 1));

    assertThat(this.store.lookup(revoked)).isEqualTo(RefreshTokenLookupResult.REVOKED);

    deleteExpiresAt(revoked.jti());

    assertThat(this.store.lookup(revoked)).isEqualTo(RefreshTokenLookupResult.REVOKED);
  }

  @Test
  void 만료가_손상된_ROTATED_토큰이라도_해시가_틀리면_HASH_MISMATCH다() {
    RefreshTokenRecord rotated = rotatedToken();
    putTokenField(rotated.jti(), "expiresAt", "corrupted");

    assertThat(lookup(rotated.jti(), someHash())).isEqualTo(RefreshTokenLookupResult.HASH_MISMATCH);
  }

  @Test
  void 만료가_손상된_REVOKED_토큰이라도_해시가_틀리면_HASH_MISMATCH다() {
    RefreshTokenRecord revoked = revokedToken();
    deleteExpiresAt(revoked.jti());

    assertThat(lookup(revoked.jti(), someHash())).isEqualTo(RefreshTokenLookupResult.HASH_MISMATCH);
  }

  @Test
  void 만료가_선행_0으로_적혀_있으면_ACTIVE로_보지_않는다() {
    String jti = newJti();
    String tokenHash = someHash();
    this.store.issueFamily(record(jti, tokenHash));
    putTokenField(jti, "expiresAt", "0" + this.expiresAt);

    assertThat(lookup(jti, tokenHash)).isEqualTo(RefreshTokenLookupResult.MISMATCHED);
  }
}
