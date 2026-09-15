package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.support.redis.ActionTokenRedisTestBase;
import com.itda.support.redis.RequiresRealRedis;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 회원 탈퇴 뒤 Refresh 계열 사후 정리를 실제 Redis 8.2.9에 대고 검증한다 (API 명세 3.2.1·3.6.2절).
 *
 * <p>여기서만 증명되는 것이 있다 — 탈퇴가 확정되면 그 사용자의 실제 계열이 모두 {@code REVOKED}가 되는 것, 다른 사용자의 계열은 그대로인 것, 탈퇴가
 * 확정되지 않은 경로에서는 Redis를 건드리지 않는 것.
 *
 * <p>대역은 MariaDB 쪽과 해시뿐이다. 조건부 UPDATE의 실제 동작은 MariaDB 테스트가 검증한다.
 */
@SpringBootTest
@RequiresRealRedis
class UserWithdrawalRedisTest extends ActionTokenRedisTestBase {

  private static final String CURRENT_PASSWORD = "충분히 긴 현재 비밀번호 문구";
  private static final long REFRESH_TTL_SECONDS = 14L * 24 * 60 * 60;

  @Autowired private RefreshTokenStore refreshTokenStore;

  private StubUserRepository users;
  private RecordingPasswordHasher hasher;
  private UserWithdrawalService service;
  private final List<String> refreshKeys = new ArrayList<>();

  @BeforeEach
  void createService() {
    this.users = new StubUserRepository(this.userId);
    this.hasher = new RecordingPasswordHasher();
    this.service =
        new UserWithdrawalService(
            this.users,
            new PasswordVerificationService(this.hasher),
            this.hasher,
            this.refreshTokenStore,
            TransactionOperations.withoutTransaction(),
            Clock.systemUTC());
  }

  /** 계열 키는 base의 정리 대상에 들어가지 않으므로 여기서 지운다. */
  @AfterEach
  void deleteKeysCreatedHere() {
    this.redis.delete(this.refreshKeys);
    this.refreshKeys.clear();
  }

  private void withdraw() {
    this.service.withdraw(new UserWithdrawalService.Command(this.userId, CURRENT_PASSWORD));
  }

  private ErrorCode errorCode() {
    return catchThrowableOfType(BusinessException.class, this::withdraw).getErrorCode();
  }

  /** 주어진 사용자의 Refresh 계열을 실제 저장소에 등록한다. */
  private String seedRefreshFamily(long owner) {
    String familyId = UUID.randomUUID().toString();
    String jti = UUID.randomUUID().toString();
    this.refreshKeys.add(this.keys.refreshToken(owner, jti));
    this.refreshKeys.add(this.keys.family(owner, familyId));
    this.refreshKeys.add(this.keys.families(owner));
    assertThat(
            this.refreshTokenStore.issueFamily(
                new RefreshTokenStore.TokenRecord(
                    owner,
                    familyId,
                    jti,
                    someHash(),
                    Instant.now().getEpochSecond() + REFRESH_TTL_SECONDS)))
        .isEqualTo(RefreshTokenStore.FamilyIssueResult.ISSUED);
    return familyId;
  }

  private Map<Object, Object> familyState(long owner, String familyId) {
    return this.redis.opsForHash().entries(this.keys.family(owner, familyId));
  }

  // ---------- 성공 ----------

  @Test
  void 탈퇴가_확정되면_그_사용자의_모든_Refresh_계열을_폐기한다() {
    String first = seedRefreshFamily(this.userId);
    String second = seedRefreshFamily(this.userId);

    assertThatCode(this::withdraw).doesNotThrowAnyException();

    assertThat(this.users.updates()).isEqualTo(1);
    assertThat(familyState(this.userId, first)).containsEntry("status", "REVOKED");
    assertThat(familyState(this.userId, second)).containsEntry("status", "REVOKED");
  }

  @Test
  void 다른_사용자의_계열은_건드리지_않는다() {
    long otherUserId = this.userId + 1_000_000L;
    String own = seedRefreshFamily(this.userId);
    String others = seedRefreshFamily(otherUserId);

    withdraw();

    assertThat(familyState(this.userId, own)).containsEntry("status", "REVOKED");
    assertThat(familyState(otherUserId, others)).containsEntry("status", "ACTIVE");
  }

  @Test
  void 정리할_계열이_없어도_탈퇴는_성공한다() {
    assertThatCode(this::withdraw).doesNotThrowAnyException();

    assertThat(this.users.updates()).isEqualTo(1);
  }

  // ---------- 확정되지 않은 탈퇴 ----------

  @Test
  void 현재_비밀번호가_다르면_계열이_ACTIVE로_남는다() {
    String familyId = seedRefreshFamily(this.userId);
    this.hasher.willReturnMatches(false);

    assertThat(errorCode()).isEqualTo(ErrorCode.CURRENT_PASSWORD_MISMATCH);

    assertThat(this.users.updates()).isZero();
    assertThat(familyState(this.userId, familyId)).containsEntry("status", "ACTIVE");
  }

  @Test
  void 조건부_갱신이_0행이면_계열이_ACTIVE로_남는다() {
    String familyId = seedRefreshFamily(this.userId);
    this.users.updateResult = 0;

    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);

    assertThat(familyState(this.userId, familyId)).containsEntry("status", "ACTIVE");
  }

  // ---------- 도우미 ----------

  /** MariaDB 쪽만 대역이다. 조건부 갱신의 실제 동작은 MariaDB 테스트가 검증한다. */
  private static final class StubUserRepository implements UserRepository {

    private final long userId;
    private final AtomicInteger updateCount = new AtomicInteger();
    private final User user;
    private int updateResult = 1;

    private StubUserRepository(long userId) {
      this.userId = userId;
      LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
      this.user =
          User.create(
              "learner" + userId,
              "$argon2id$v=19$m=19456,t=2,p=1$old-salt$old-hash",
              UserRole.LEARNER,
              Email.of("a@example.com"),
              now);
      this.user.verifyEmail(now);
    }

    int updates() {
      return this.updateCount.get();
    }

    @Override
    public Optional<User> findById(Long id) {
      return id == this.userId ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public int withdraw(
        long userId,
        long expectedRowVersion,
        String expectedPasswordHash,
        long expectedAuthVersion,
        String pseudonymUsername,
        Email pseudonymEmail,
        String discardedPasswordHash,
        LocalDateTime now) {
      this.updateCount.incrementAndGet();
      return this.updateResult;
    }

    @Override
    public int resetPassword(
        long userId,
        long expectedRowVersion,
        String expectedPasswordHash,
        long expectedAuthVersion,
        String newPasswordHash,
        LocalDateTime now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int recordLoginFailure(
        long userId, int maxAttempts, LocalDateTime lockedUntil, LocalDateTime now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int recordLoginSuccess(
        long userId, long expectedRowVersion, long expectedAuthVersion, LocalDateTime now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public User save(User user) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<User> findByUsername(String username) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<User> findByEmail(Email email) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean existsByUsername(String username) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean existsByEmail(Email email) {
      throw new UnsupportedOperationException();
    }
  }
}
