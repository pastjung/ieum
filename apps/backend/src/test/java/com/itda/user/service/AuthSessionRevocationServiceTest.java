package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.repository.RefreshTokenStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 로그아웃의 결과별 판정을 저장소 없이 고정한다 (API 명세 3.3·3.3.1·3.6.2절).
 *
 * <p>이 유스케이스는 {@link com.itda.user.repository.UserRepository}를 <b>생성자에서 받지 않는다</b>. "MariaDB 조회는
 * 로그아웃 완료 조건이 아니다"라는 계약이 타입 수준에서 지켜지는지가 이 테스트가 고정하는 성질 중 하나다.
 */
class AuthSessionRevocationServiceTest {

  private static final long USER_ID = 42L;

  private String familyId;
  private String jti;
  private String tokenHash;

  private RecordingVerifier verifier;
  private RecordingStore store;
  private AuthSessionRevocationService service;

  @BeforeEach
  void setUp() {
    this.familyId = UUID.randomUUID().toString();
    this.jti = UUID.randomUUID().toString();
    this.tokenHash = "h".repeat(43);
    this.verifier =
        new RecordingVerifier(
            new RefreshTokenVerifier.Verification.Verified(
                USER_ID, this.familyId, this.jti, this.tokenHash, 3L, 1_000L, 2_000L));
    this.store = new RecordingStore();
    this.service = new AuthSessionRevocationService(this.verifier, this.store);
  }

  private void revoke() {
    this.service.revoke(new AuthSessionRevocationService.Command("refresh.compact.value"));
  }

  private ErrorCode errorCode() {
    return catchThrowableOfType(BusinessException.class, this::revoke).getErrorCode();
  }

  // ---------- 폐기 성공 ----------

  @ParameterizedTest
  @EnumSource(
      value = RefreshTokenStore.LookupResult.class,
      names = {"ACTIVE", "ROTATED", "REVOKED"})
  void 식별된_토큰은_계열을_폐기하고_성공한다(RefreshTokenStore.LookupResult lookup) {
    this.store.willLookup(lookup);

    assertThatCode(this::revoke).doesNotThrowAnyException();

    assertThat(this.store.revokedFamilies).containsExactly(this.familyId);
  }

  @ParameterizedTest
  @EnumSource(
      value = RefreshTokenStore.LookupResult.class,
      names = {"ACTIVE", "ROTATED", "REVOKED"})
  void 계열_key가_이미_사라졌어도_성공이다(RefreshTokenStore.LookupResult lookup) {
    this.store.willLookup(lookup);
    this.store.willRevoke(RefreshTokenStore.FamilyRevocationResult.NOT_FOUND);

    // 계열 없이는 회전이 성립하지 않는다. Redis 정상 응답으로 확인한 결정적 결과다.
    assertThatCode(this::revoke).doesNotThrowAnyException();
    assertThat(this.store.revokedFamilies).containsExactly(this.familyId);
  }

  @Test
  void 회전된_이전_토큰도_재사용으로_보고_계열을_폐기한다() {
    this.store.willLookup(RefreshTokenStore.LookupResult.ROTATED);

    assertThatCode(this::revoke).doesNotThrowAnyException();

    // 로그아웃 경로로 재사용 탐지를 우회할 수 없어야 한다.
    assertThat(this.store.revokedFamilies).containsExactly(this.familyId);
  }

  @Test
  void 이미_폐기된_계열은_추가_명령_없이_멱등하게_성공이다() {
    this.store.willLookup(RefreshTokenStore.LookupResult.FAMILY_REVOKED);

    assertThatCode(this::revoke).doesNotThrowAnyException();

    assertThat(this.store.revokedFamilies).isEmpty();
  }

  // ---------- 401 ----------

  @Test
  void JWT가_무효면_401이고_저장소를_건드리지_않는다() {
    this.verifier.willReject();

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.lookups).isZero();
    assertThat(this.store.revokedFamilies).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = RefreshTokenStore.LookupResult.class,
      names = {"NOT_FOUND", "HASH_MISMATCH"})
  void 상태가_식별되지_않으면_401이고_폐기하지_않는다(RefreshTokenStore.LookupResult lookup) {
    this.store.willLookup(lookup);

    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.revokedFamilies).isEmpty();
  }

  @Test
  void 상태_불일치는_계열을_닫은_뒤_401이다() {
    this.store.willLookup(RefreshTokenStore.LookupResult.MISMATCHED);

    // MISMATCHED에는 "ACTIVE지만 계열의 현재 토큰이 아님"(재사용 의심)이 섞여 있다. 재사용이 아님을 확정할 수 없으므로
    // fail-closed로 계열을 닫되, 외부 응답은 상태 불일치의 401이다.
    assertThat(errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(this.store.revokedFamilies).containsExactly(this.familyId);
  }

  @Test
  void 상태_불일치의_폐기_실패는_503이다() {
    this.store.willLookup(RefreshTokenStore.LookupResult.MISMATCHED);
    this.store.willRevoke(RefreshTokenStore.FamilyRevocationResult.UNAVAILABLE);

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  // ---------- 503 ----------

  @Test
  void 조회_장애는_401이_아니라_503이다() {
    this.store.willLookup(RefreshTokenStore.LookupResult.UNAVAILABLE);

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
    assertThat(this.store.revokedFamilies).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = RefreshTokenStore.LookupResult.class,
      names = {"ACTIVE", "ROTATED", "REVOKED"})
  void 폐기_명령_실패는_성공으로_보고하지_않는다(RefreshTokenStore.LookupResult lookup) {
    this.store.willLookup(lookup);
    this.store.willRevoke(RefreshTokenStore.FamilyRevocationResult.UNAVAILABLE);

    // 서버 상태를 정리하지 못한 채 "로그아웃됐다"고 답하면 사용자는 세션이 살아 있는 줄 모른다.
    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  // ---------- 입력 ----------

  @Test
  void 검증된_토큰의_식별자와_해시로만_조회한다() {
    revoke();

    RefreshTokenStore.TokenRecord submitted = this.store.lookedUp.get(0);
    assertThat(submitted.userId()).isEqualTo(USER_ID);
    assertThat(submitted.familyId()).isEqualTo(this.familyId);
    assertThat(submitted.jti()).isEqualTo(this.jti);
    // 유스케이스가 원문을 해시하지 않는다.
    assertThat(submitted.tokenHash()).isEqualTo(this.tokenHash);
  }

  @Test
  void 입력의_toString에_토큰이_없다() {
    assertThat(new AuthSessionRevocationService.Command("refresh.compact.value"))
        .hasToString("AuthSessionRevocationCommand[REDACTED]");
  }

  @Test
  void 오류_message에_토큰도_해시도_식별자도_없다() {
    this.store.willLookup(RefreshTokenStore.LookupResult.NOT_FOUND);

    BusinessException thrown = catchThrowableOfType(BusinessException.class, this::revoke);

    assertThat(thrown.getErrorCode().getMessage())
        .doesNotContain(this.tokenHash)
        .doesNotContain(this.jti)
        .doesNotContain(this.familyId);
    assertThat(thrown.getCause()).isNull();
  }

  // ---------- 도우미 ----------

  private static final class RecordingVerifier implements RefreshTokenVerifier {

    private final RefreshTokenVerifier.Verification.Verified verified;
    private boolean reject;

    private RecordingVerifier(RefreshTokenVerifier.Verification.Verified verified) {
      this.verified = verified;
    }

    void willReject() {
      this.reject = true;
    }

    @Override
    public RefreshTokenVerifier.Verification verify(String compactToken) {
      return this.reject ? new RefreshTokenVerifier.Verification.Invalid() : this.verified;
    }
  }

  private static final class RecordingStore implements RefreshTokenStore {

    private final List<RefreshTokenStore.TokenRecord> lookedUp = new ArrayList<>();
    private final List<String> revokedFamilies = new ArrayList<>();

    private RefreshTokenStore.LookupResult lookupResult = RefreshTokenStore.LookupResult.ACTIVE;
    private RefreshTokenStore.FamilyRevocationResult revocationResult =
        RefreshTokenStore.FamilyRevocationResult.REVOKED;
    private int lookups;

    void willLookup(RefreshTokenStore.LookupResult result) {
      this.lookupResult = result;
    }

    void willRevoke(RefreshTokenStore.FamilyRevocationResult result) {
      this.revocationResult = result;
    }

    @Override
    public RefreshTokenStore.LookupResult lookup(RefreshTokenStore.TokenRecord submitted) {
      this.lookups++;
      this.lookedUp.add(submitted);
      return this.lookupResult;
    }

    @Override
    public RefreshTokenStore.FamilyIssueResult issueFamily(
        RefreshTokenStore.TokenRecord firstToken) {
      throw new UnsupportedOperationException("로그아웃은 계열을 만들지 않는다.");
    }

    @Override
    public RefreshTokenStore.RotationResult rotate(
        RefreshTokenStore.TokenRecord current, RefreshTokenStore.TokenRecord next) {
      throw new UnsupportedOperationException("로그아웃은 회전하지 않는다.");
    }

    @Override
    public RefreshTokenStore.FamilyRevocationResult revokeFamily(long userId, String familyId) {
      this.revokedFamilies.add(familyId);
      return this.revocationResult;
    }

    @Override
    public RefreshTokenStore.AllFamiliesRevocationResult revokeAllFamilies(long userId) {
      throw new UnsupportedOperationException();
    }
  }
}
