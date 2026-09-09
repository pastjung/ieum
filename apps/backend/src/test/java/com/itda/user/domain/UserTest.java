package com.itda.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class UserTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 12, 0);

  private User activeLearner() {
    return User.create("learner01", "argon2id$hash", UserRole.LEARNER, Email.of("a@b.com"), NOW);
  }

  @Test
  void 생성된_계정은_ACTIVE이고_실패_횟수가_0이다() {
    User user = activeLearner();

    assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(user.getFailedLoginCount()).isZero();
    assertThat(user.getLockedUntil()).isNull();
    assertThat(user.getCreatedAt()).isEqualTo(NOW);
    assertThat(user.getUpdatedAt()).isEqualTo(NOW);
  }

  @Test
  void 이메일_인증_전에는_ACTIVE여도_로그인을_허용하지_않는다() {
    User user = activeLearner();

    assertThat(user.isEmailVerified()).isFalse();
    assertThat(user.isLoginAllowed(NOW)).isFalse();
  }

  @Test
  void 이메일_인증_후에는_로그인을_허용한다() {
    User user = activeLearner();

    user.verifyEmail(NOW);

    assertThat(user.isEmailVerified()).isTrue();
    assertThat(user.isLoginAllowed(NOW)).isTrue();
  }

  @Test
  void 이미_인증된_계정의_인증_시각은_덮어쓰지_않는다() {
    User user = activeLearner();
    user.verifyEmail(NOW);

    user.verifyEmail(NOW.plusDays(1));

    assertThat(user.getEmailVerifiedAt()).isEqualTo(NOW);
  }

  @Test
  void 로그인_실패가_4회면_아직_제한하지_않는다() {
    User user = activeLearner();

    for (int i = 0; i < User.MAX_FAILED_LOGIN_ATTEMPTS - 1; i++) {
      user.recordLoginFailure(NOW);
    }

    assertThat(user.getFailedLoginCount()).isEqualTo(4);
    assertThat(user.isLocked(NOW)).isFalse();
  }

  @Test
  void 로그인_실패가_5회면_10분간_제한한다() {
    User user = activeLearner();

    for (int i = 0; i < User.MAX_FAILED_LOGIN_ATTEMPTS; i++) {
      user.recordLoginFailure(NOW);
    }

    assertThat(user.getLockedUntil()).isEqualTo(NOW.plusMinutes(10));
    assertThat(user.isLocked(NOW)).isTrue();
    assertThat(user.isLocked(NOW.plusMinutes(9))).isTrue();
    assertThat(user.isLocked(NOW.plusMinutes(10))).isFalse();
  }

  @Test
  void 제한된_계정은_이메일_인증을_마쳐도_로그인을_허용하지_않는다() {
    User user = activeLearner();
    user.verifyEmail(NOW);

    for (int i = 0; i < User.MAX_FAILED_LOGIN_ATTEMPTS; i++) {
      user.recordLoginFailure(NOW);
    }

    assertThat(user.isLoginAllowed(NOW)).isFalse();
  }

  @Test
  void 로그인_성공은_실패_상태를_초기화한다() {
    User user = activeLearner();
    for (int i = 0; i < User.MAX_FAILED_LOGIN_ATTEMPTS; i++) {
      user.recordLoginFailure(NOW);
    }

    user.resetLoginFailures(NOW);

    assertThat(user.getFailedLoginCount()).isZero();
    assertThat(user.getLockedUntil()).isNull();
    assertThat(user.isLocked(NOW)).isFalse();
  }

  @Test
  void 탈퇴는_상태_전이와_가명화를_함께_적용한다() {
    User user = activeLearner();
    user.verifyEmail(NOW);
    LocalDateTime withdrawnAt = NOW.plusDays(30);

    user.withdraw(
        "withdrawn-1", Email.of("withdrawn-1@example.invalid"), "discarded$hash", withdrawnAt);

    assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    assertThat(user.isWithdrawn()).isTrue();
    assertThat(user.getWithdrawnAt()).isEqualTo(withdrawnAt);
    assertThat(user.getEmailVerifiedAt()).isNull();
    assertThat(user.getUsername()).isEqualTo("withdrawn-1");
    assertThat(user.getEmail().getValue()).isEqualTo("withdrawn-1@example.invalid");
    assertThat(user.isLoginAllowed(withdrawnAt)).isFalse();
  }

  @Test
  void 탈퇴한_계정은_다시_변경할_수_없다() {
    User user = activeLearner();
    user.withdraw("withdrawn-1", Email.of("w@example.invalid"), "discarded$hash", NOW);

    assertThatThrownBy(() -> user.changeLevel("L2", NOW)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> user.verifyEmail(NOW)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> user.changePasswordHash("x", NOW))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 생성_시_열린_단계는_없다() {
    assertThat(activeLearner().getLevel()).isNull();
  }

  @Test
  void 아이디는_앞뒤_공백을_제거해_저장한다() {
    User user = User.create("  learner01  ", "hash", UserRole.LEARNER, Email.of("a@b.com"), NOW);

    assertThat(user.getUsername()).isEqualTo("learner01");
  }

  @Test
  void 아이디가_비어_있으면_거부한다() {
    assertThatThrownBy(() -> User.create("   ", "hash", UserRole.LEARNER, Email.of("a@b.com"), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
