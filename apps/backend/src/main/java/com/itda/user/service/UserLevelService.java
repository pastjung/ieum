package com.itda.user.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.problem.domain.Level;
import com.itda.user.domain.User;
import com.itda.user.repository.UserJpaRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학습자가 스스로 고르는 학습 단계.
 *
 * <p>{@code users.level}은 "열린 최고 단계"(ERD 4.7절)이고 문항 출제의 기준이다. 그런데 이 값을 설정하는 코드가 오랫동안 없었다 —
 * {@link User#changeLevel}은 정의만 있고 호출부가 한 줄도 없어 모든 계정이 {@code NULL}이었다. 가입 화면이 바뀌면서 단계를 고르는
 * 단계가 빠진 것으로 확인됐다.
 *
 * <p><b>API 명세와 다른 길이다.</b> {@code docs/product/api-spec.md}는 자가 진단 6문항의 답을 첫 세션 생성의 {@code
 * initialUnlockedStageId}로 실어 보내 원자적으로 기록하고 "별도 진단 endpoint 없음"이라고 정했다. 그 흐름은 구현되지 않았고({@code
 * NewStudyRequest}에 그 필드가 없다), 설정 화면에서 직접 고르는 쪽으로 가기로 했다. 명세도 함께 갱신해야 한다.
 *
 * <p>그래서 명세의 단조성 규칙("하위 복습은 level을 낮추지 않는다")도 여기서는 강제하지 않는다. 학습자가 너무 어려운 단계를 골랐을 때 되돌릴 길이
 * 없으면 학습을 이어갈 수 없기 때문이다.
 */
@Service
public class UserLevelService {

  private final UserJpaRepository userJpaRepository;

  public UserLevelService(UserJpaRepository userJpaRepository) {
    this.userJpaRepository = userJpaRepository;
  }

  /**
   * 학습 단계를 기록한다.
   *
   * <p>{@code null}은 받지 않는다. {@code null}은 "아직 고르지 않음"을 뜻하는 초기 상태이고, 설정 화면이 되돌릴 수 있는 값이 아니다.
   */
  @Transactional
  public void changeLevel(long userId, Level level) {
    if (level == null) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST_INPUT);
    }
    User user =
        userJpaRepository
            .findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    user.changeLevel(level, LocalDateTime.now(ZoneOffset.UTC));
  }
}
