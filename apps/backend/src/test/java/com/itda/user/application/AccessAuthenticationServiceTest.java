package com.itda.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.support.security.StubAccessTokenVerifier;
import com.itda.support.security.StubUserRepository;
import com.itda.user.application.port.out.AccessTokenVerification;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccessAuthenticationServiceTest {

  private static final long USER_ID = 42L;

  /** 스텁 검증기에 넘길 뿐 내용은 쓰이지 않는 자리표시자 값이다. */
  private static final String SAMPLE = "test-compact-value";

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 12, 12, 0);

  private StubAccessTokenVerifier verifier;
  private StubUserRepository userRepository;
  private AccessAuthenticationService service;

  @BeforeEach
  void setUp() {
    this.verifier = new StubAccessTokenVerifier();
    this.userRepository = new StubUserRepository();
    this.service = new AccessAuthenticationService(this.verifier, this.userRepository);
  }

  private static User verifiedLearner() {
    User user =
        User.create("learner01", "argon2id$hash", UserRole.LEARNER, Email.of("a@b.com"), NOW);
    user.verifyEmail(NOW);
    return user;
  }

  private void tokenSays(long userId, UserRole role, long authVersion) {
    this.verifier.willReturn(new AccessTokenVerification.Verified(userId, role, authVersion));
  }

  @Test
  void 토큰과_저장소_상태가_모두_맞으면_인증된다() {
    tokenSays(USER_ID, UserRole.LEARNER, 0);
    this.userRepository.register(USER_ID, verifiedLearner());

    AccessAuthenticationResult result = this.service.authenticate(SAMPLE);

    assertThat(result)
        .isEqualTo(new AccessAuthenticationResult.Authenticated(USER_ID, UserRole.LEARNER));
    assertThat(this.verifier.verifiedTokens()).containsExactly(SAMPLE);
  }

  @Test
  void 역할은_저장소가_아니라_검증된_토큰_값을_쓴다() {
    // 계약(API 명세 3.1절)이 정한 DB 재검증 항목은 사용자 존재·status·이메일 인증·authVersion 네 가지뿐이다.
    // role은 그 목록에 없으므로 DB 값과 비교하지도, DB 값으로 대체하지도 않는다.
    tokenSays(USER_ID, UserRole.ADMIN, 0);
    this.userRepository.register(USER_ID, verifiedLearner());

    AccessAuthenticationResult result = this.service.authenticate(SAMPLE);

    assertThat(result)
        .isEqualTo(new AccessAuthenticationResult.Authenticated(USER_ID, UserRole.ADMIN));
  }

  @Test
  void role이_DB와_달라도_401로_만들지_않는다() {
    tokenSays(USER_ID, UserRole.INSTRUCTOR, 0);
    this.userRepository.register(USER_ID, verifiedLearner());

    assertThat(this.service.authenticate(SAMPLE))
        .isNotInstanceOf(AccessAuthenticationResult.Rejected.class);
  }

  @Test
  void 토큰_검증에_실패하면_저장소를_조회하지_않는다() {
    this.verifier.willReturn(new AccessTokenVerification.Invalid());

    AccessAuthenticationResult result = this.service.authenticate(SAMPLE);

    assertThat(result).isInstanceOf(AccessAuthenticationResult.Rejected.class);
    assertThat(this.userRepository.findByIdCallCount()).isZero();
  }

  @Test
  void 사용자가_없으면_거부한다() {
    tokenSays(USER_ID, UserRole.LEARNER, 0);

    assertThat(this.service.authenticate(SAMPLE))
        .isInstanceOf(AccessAuthenticationResult.Rejected.class);
  }

  @Test
  void 이메일_미인증_계정은_거부한다() {
    tokenSays(USER_ID, UserRole.LEARNER, 0);
    this.userRepository.register(
        USER_ID,
        User.create("learner02", "argon2id$hash", UserRole.LEARNER, Email.of("c@d.com"), NOW));

    assertThat(this.service.authenticate(SAMPLE))
        .isInstanceOf(AccessAuthenticationResult.Rejected.class);
  }

  @Test
  void 탈퇴한_계정은_거부한다() {
    tokenSays(USER_ID, UserRole.LEARNER, 0);
    User user = verifiedLearner();
    user.withdraw("withdrawn01", Email.of("x@y.com"), "argon2id$discarded", NOW);
    this.userRepository.register(USER_ID, user);

    assertThat(this.service.authenticate(SAMPLE))
        .isInstanceOf(AccessAuthenticationResult.Rejected.class);
  }

  @Test
  void authVersion이_다르면_거부한다() {
    tokenSays(USER_ID, UserRole.LEARNER, 1);
    this.userRepository.register(USER_ID, verifiedLearner());

    assertThat(this.service.authenticate(SAMPLE))
        .isInstanceOf(AccessAuthenticationResult.Rejected.class);
  }

  @Test
  void 네_가지_거부_사유는_서로_구분되지_않는다() {
    AccessAuthenticationResult rejected = new AccessAuthenticationResult.Rejected();

    tokenSays(USER_ID, UserRole.LEARNER, 0);
    assertThat(this.service.authenticate(SAMPLE)).isEqualTo(rejected);

    User withdrawn = verifiedLearner();
    withdrawn.withdraw("withdrawn02", Email.of("p@q.com"), "argon2id$discarded", NOW);
    this.userRepository.register(USER_ID, withdrawn);
    assertThat(this.service.authenticate(SAMPLE)).isEqualTo(rejected);

    this.userRepository.register(
        USER_ID,
        User.create("learner03", "argon2id$hash", UserRole.LEARNER, Email.of("e@f.com"), NOW));
    assertThat(this.service.authenticate(SAMPLE)).isEqualTo(rejected);

    tokenSays(USER_ID, UserRole.LEARNER, 9);
    this.userRepository.register(USER_ID, verifiedLearner());
    assertThat(this.service.authenticate(SAMPLE)).isEqualTo(rejected);
  }

  @Test
  void 저장소_명령이_실패하면_인증_실패로_위장하지_않는다() {
    tokenSays(USER_ID, UserRole.LEARNER, 0);
    this.userRepository.willFailWithDataAccessException();

    AccessAuthenticationResult result = this.service.authenticate(SAMPLE);

    assertThat(result).isInstanceOf(AccessAuthenticationResult.Unavailable.class);
    assertThat(result).isNotInstanceOf(AccessAuthenticationResult.Rejected.class);
  }

  @Test
  void 트랜잭션을_시작하지_못해도_저장소_장애로_다룬다() {
    // 커넥션을 얻지 못한 실패는 DataAccessException이 아니라 TransactionException 계열로 나온다.
    // 한쪽만 잡으면 이 경우가 그대로 전파되어 계약에 없는 500이 된다.
    tokenSays(USER_ID, UserRole.LEARNER, 0);
    this.userRepository.willFail();

    AccessAuthenticationResult result = this.service.authenticate(SAMPLE);

    assertThat(result).isInstanceOf(AccessAuthenticationResult.Unavailable.class);
  }

  @Test
  void 프로그래밍_오류는_저장소_장애로_숨기지_않는다() {
    tokenSays(USER_ID, UserRole.LEARNER, 0);
    this.userRepository.willFailWithProgrammingError();

    assertThatThrownBy(() -> this.service.authenticate(SAMPLE))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 인증_결과의_toString에_사용자_정보가_없다() {
    tokenSays(USER_ID, UserRole.ADMIN, 0);
    this.userRepository.register(USER_ID, verifiedLearner());

    assertThat(this.service.authenticate(SAMPLE).toString())
        .isEqualTo("AccessAuthenticationResult.Authenticated[REDACTED]")
        .doesNotContain("42")
        .doesNotContain("ADMIN");
  }
}
