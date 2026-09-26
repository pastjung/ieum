package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.problem.domain.Level;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.UserJpaRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 학습 단계 설정.
 *
 * <p>{@code users.level}은 오랫동안 아무도 설정하지 않아 모든 계정이 {@code NULL}이었다 — {@link User#changeLevel}은 정의만
 * 있고 호출부가 없었다. 그 탓에 복습 출제가 누구에게도 열리지 않았다.
 */
@ExtendWith(MockitoExtension.class)
class UserLevelServiceTest {

  private static final long USER_ID = 1L;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 25, 10, 0);

  @Mock private UserJpaRepository userJpaRepository;

  private UserLevelService service;

  @BeforeEach
  void setUp() {
    service = new UserLevelService(userJpaRepository);
  }

  @Test
  void 학습_단계를_기록한다() {
    User user = User.create("chwogus", "argon2id$hash", UserRole.LEARNER, Email.of("a@b.com"), NOW);
    given(userJpaRepository.findById(USER_ID)).willReturn(Optional.of(user));

    service.changeLevel(USER_ID, Level.WORD);

    assertThat(user.getLevel()).isEqualTo(Level.WORD);
  }

  @Test
  void 이미_단계가_있어도_바꿀_수_있다() {
    // 설정 화면에서 학습자가 스스로 고른다. 낮추는 것도 허용한다 — 너무 어려운 단계를 골랐을 때
    // 되돌릴 길이 없으면 학습을 이어갈 수 없다
    User user = User.create("chwogus", "argon2id$hash", UserRole.LEARNER, Email.of("a@b.com"), NOW);
    given(userJpaRepository.findById(USER_ID)).willReturn(Optional.of(user));
    service.changeLevel(USER_ID, Level.SENTENCE);

    service.changeLevel(USER_ID, Level.CONSONANT);

    assertThat(user.getLevel()).isEqualTo(Level.CONSONANT);
  }

  @Test
  void 없는_사용자면_거부한다() {
    given(userJpaRepository.findById(USER_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.changeLevel(USER_ID, Level.WORD))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.USER_NOT_FOUND);
  }

  @Test
  void 단계를_비우려_하면_거부한다() {
    // null은 "아직 고르지 않음"이라 설정 화면이 만들 수 있는 상태가 아니다
    assertThatThrownBy(() -> service.changeLevel(USER_ID, null))
        .isInstanceOf(BusinessException.class);
  }
}
