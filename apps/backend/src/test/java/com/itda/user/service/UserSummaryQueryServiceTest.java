package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.problem.domain.Level;
import com.itda.support.security.StubUserRepository;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 내 정보 조회의 판정을 고정한다 (API 명세 3.2.1·3.6.2절).
 *
 * <p>여기서 확인하는 것은 "무엇을 근거로 응답을 만들고, 무엇을 401과 503으로 나누는가"다.
 */
class UserSummaryQueryServiceTest {

  private static final long USER_ID = 7L;
  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 13, 9, 0);

  private StubUserRepository users;
  private UserSummaryQueryService service;

  @BeforeEach
  void setUp() {
    this.users = new StubUserRepository();
    this.service = new UserSummaryQueryService(this.users);
  }

  private User verifiedLearner() {
    User user =
        User.create(
            "learner01", "argon2id$hash", UserRole.LEARNER, Email.of("a@example.com"), CREATED_AT);
    user.verifyEmail(CREATED_AT.plusMinutes(1));
    return user;
  }

  private ErrorCode errorCode() {
    return catchThrowableOfType(BusinessException.class, () -> this.service.getSummary(USER_ID))
        .getErrorCode();
  }

  @Test
  void 현재_DB_값으로_요약을_만든다() {
    User user = verifiedLearner();
    user.changeLevel(Level.WORD, CREATED_AT.plusDays(1));
    this.users.register(USER_ID, user);

    UserSummary summary = this.service.getSummary(USER_ID);

    assertThat(summary.userId()).isEqualTo(USER_ID);
    assertThat(summary.username()).isEqualTo("learner01");
    assertThat(summary.email()).isEqualTo("a@example.com");
    assertThat(summary.role()).isEqualTo(UserRole.LEARNER);
    assertThat(summary.emailVerified()).isTrue();
    assertThat(summary.level()).isEqualTo(Level.WORD);
    assertThat(summary.createdAt()).isEqualTo(CREATED_AT);
  }

  @Test
  void 자가_진단_전에는_level이_null이다() {
    this.users.register(USER_ID, verifiedLearner());

    assertThat(this.service.getSummary(USER_ID).level()).isNull();
  }

  @Test
  void 사용자가_없으면_404가_아니라_401이다() {
    // 인증 직후 사라진 사용자의 존재 여부를 알려 주지 않는다 (3.5절).
    assertThat(errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
  }

  @Test
  void 탈퇴한_계정은_401이다() {
    User user = verifiedLearner();
    user.withdraw(
        "withdrawn-1",
        Email.of("withdrawn-1@example.invalid"),
        "discarded$hash",
        CREATED_AT.plusDays(2));
    this.users.register(USER_ID, user);

    assertThat(errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
  }

  @Test
  void 이메일_미인증_계정은_401이다() {
    User user =
        User.create(
            "learner01", "argon2id$hash", UserRole.LEARNER, Email.of("a@example.com"), CREATED_AT);
    this.users.register(USER_ID, user);

    assertThat(errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
  }

  @Test
  void 조회_장애는_401이_아니라_503이다() {
    this.users.register(USER_ID, verifiedLearner());
    this.users.willFailWithDataAccessException();

    // 장애를 인증 실패로 위장하면 클라이언트가 재로그인으로 잘못 복구한다 (3.6.2절).
    assertThat(errorCode()).isEqualTo(ErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  void 요약_toString에_개인정보가_없다() {
    this.users.register(USER_ID, verifiedLearner());

    assertThat(this.service.getSummary(USER_ID)).hasToString("UserSummary[REDACTED]");
  }

  @Test
  void 실패_message에_사용자_정보가_없다() {
    User user = verifiedLearner();
    this.users.register(USER_ID, user);
    this.users.willFailWithDataAccessException();

    BusinessException thrown =
        catchThrowableOfType(BusinessException.class, () -> this.service.getSummary(USER_ID));

    assertThat(thrown.getErrorCode().getMessage())
        .doesNotContain("learner01")
        .doesNotContain("a@example.com")
        .doesNotContain(Long.toString(USER_ID));
  }
}
