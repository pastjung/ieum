package com.itda.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.redis.RequiresRealRedis;
import com.itda.user.service.CompactTokenHasher;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 사용자 계열 전체 폐기를 실제 Redis에 대고 검증한다 (API 명세 3.6.1·3.6.2절).
 *
 * <p>비밀번호 재설정의 사후 정리가 쓰는 연산이다. 여기서 증명하는 것은 {@code families} 인덱스만으로 사용자의 계열을 찾아 닫는다는 것, 구간 크기를 넘는
 * 인덱스도 끝까지 읽는다는 것, 토큰 상태·만료·인덱스와 다른 사용자의 key를 건드리지 않는다는 것이다.
 *
 * <p>토큰 원문을 만들지 않는다. 저장소가 다루는 값은 해시뿐이다.
 */
@SpringBootTest
@RequiresRealRedis
class LuaRefreshTokenFamiliesRevocationRedisTest extends RefreshTokenRedisTestBase {

  private static String someHash() {
    return CompactTokenHasher.hash("header.payload." + UUID.randomUUID());
  }

  /** 계열과 첫 토큰을 등록하고 그 jti를 돌려준다. */
  private String issue(String familyId) {
    String jti = newJti();
    assertThat(this.store.issueFamily(record(familyId, jti, someHash())))
        .isEqualTo(RefreshTokenStore.FamilyIssueResult.ISSUED);
    return jti;
  }

  private RefreshTokenStore.AllFamiliesRevocationResult revokeAll() {
    return this.store.revokeAllFamilies(this.userId);
  }

  @Test
  void 인덱스에_등록된_모든_계열을_폐기한다() {
    String second = newFamilyId();
    String third = newFamilyId();
    issue(this.familyId);
    issue(second);
    issue(third);

    assertThat(revokeAll()).isEqualTo(RefreshTokenStore.AllFamiliesRevocationResult.REVOKED);

    for (String familyId : List.of(this.familyId, second, third)) {
      assertThat(familyState(familyId)).containsEntry("status", "REVOKED");
    }
  }

  @Test
  void 토큰_상태와_만료와_인덱스는_건드리지_않는다() {
    String jti = issue(this.familyId);
    Long tokenExpireTime = expireTimeOfToken(jti);
    Long familyExpireTime = expireTimeOfFamily();
    Long familiesExpireTime = expireTimeOfFamilies();
    Double score = familiesScore(this.familyId);

    revokeAll();

    // 폐기는 계열 hash의 상태만 바꾼다. 재사용 판별에 필요한 흔적과 만료를 앞당기지 않는다.
    assertThat(tokenState(jti)).containsEntry("status", "ACTIVE");
    assertThat(familyState()).containsEntry("currentJti", jti);
    assertThat(expireTimeOfToken(jti)).isEqualTo(tokenExpireTime);
    assertThat(expireTimeOfFamily()).isEqualTo(familyExpireTime);
    assertThat(expireTimeOfFamilies()).isEqualTo(familiesExpireTime);
    assertThat(familiesScore(this.familyId)).isEqualTo(score);
  }

  @Test
  void 회전된_계열도_폐기되어_이후_회전은_FAMILY_REVOKED다() {
    String currentJti = newJti();
    String currentHash = someHash();
    this.store.issueFamily(record(currentJti, currentHash));
    String nextJti = newJti();
    String nextHash = someHash();
    assertThat(this.store.rotate(record(currentJti, currentHash), record(nextJti, nextHash)))
        .isEqualTo(RefreshTokenStore.RotationResult.ROTATED);

    assertThat(revokeAll()).isEqualTo(RefreshTokenStore.AllFamiliesRevocationResult.REVOKED);

    assertThat(this.store.rotate(record(nextJti, nextHash), record(newJti(), someHash())))
        .isEqualTo(RefreshTokenStore.RotationResult.FAMILY_REVOKED);
  }

  @Test
  void 인덱스가_없으면_REVOKED이고_key를_만들지_않는다() {
    assertThat(revokeAll()).isEqualTo(RefreshTokenStore.AllFamiliesRevocationResult.REVOKED);

    assertThat(this.redis.hasKey(this.keys.families(this.userId))).isFalse();
    assertThat(this.redis.hasKey(this.keys.family(this.userId, this.familyId))).isFalse();
  }

  @Test
  void 계열_key가_이미_사라진_member는_다시_만들지_않는다() {
    issue(this.familyId);
    // 계열 hash가 TTL로 먼저 사라지고 인덱스 member만 남은 상태다.
    this.redis.delete(this.keys.family(this.userId, this.familyId));

    assertThat(revokeAll()).isEqualTo(RefreshTokenStore.AllFamiliesRevocationResult.REVOKED);

    assertThat(this.redis.hasKey(this.keys.family(this.userId, this.familyId))).isFalse();
  }

  @Test
  void 구간_크기보다_많은_계열도_끝까지_폐기한다() {
    int count = LuaRefreshTokenStore.FAMILY_INDEX_PAGE_SIZE * 2 + 5;
    List<String> familyIds = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String familyId = newFamilyId();
      issue(familyId);
      familyIds.add(familyId);
    }

    assertThat(revokeAll()).isEqualTo(RefreshTokenStore.AllFamiliesRevocationResult.REVOKED);

    assertThat(this.redis.opsForZSet().size(this.keys.families(this.userId)))
        .isEqualTo((long) count);
    assertThat(familyIds)
        .allSatisfy(
            familyId -> assertThat(familyState(familyId)).containsEntry("status", "REVOKED"));
  }

  @Test
  void 계약_형식이_아닌_member가_있으면_나머지는_폐기하고_UNAVAILABLE이다() {
    issue(this.familyId);
    this.redis.opsForZSet().add(this.keys.families(this.userId), "not-a-family-id", this.expiresAt);

    assertThat(revokeAll()).isEqualTo(RefreshTokenStore.AllFamiliesRevocationResult.UNAVAILABLE);

    assertThat(familyState()).containsEntry("status", "REVOKED");
  }

  @Test
  void 다른_사용자의_계열은_건드리지_않는다() {
    long otherUserId = this.userId + 500_000L;
    String otherFamilyId = UUID.randomUUID().toString();
    String otherJti = UUID.randomUUID().toString();
    try {
      this.store.issueFamily(
          new RefreshTokenStore.TokenRecord(
              otherUserId, otherFamilyId, otherJti, someHash(), this.expiresAt));
      issue(this.familyId);

      revokeAll();

      assertThat(
              this.redis.opsForHash().get(this.keys.family(otherUserId, otherFamilyId), "status"))
          .isEqualTo("ACTIVE");
    } finally {
      this.redis.delete(
          List.of(
              this.keys.refreshToken(otherUserId, otherJti),
              this.keys.family(otherUserId, otherFamilyId),
              this.keys.families(otherUserId)));
    }
  }
}
