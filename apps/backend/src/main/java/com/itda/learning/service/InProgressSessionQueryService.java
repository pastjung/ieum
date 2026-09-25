package com.itda.learning.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.learning.domain.LearningSession;
import com.itda.learning.domain.LearningSessionRepository;
import com.itda.problem.domain.ProblemSession;
import com.itda.problem.repository.ProblemSessionRepository;
import com.itda.user.domain.User;
import com.itda.user.repository.UserJpaRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 진행 중인 학습 세션 하나.
 *
 * <p>홈 화면의 "이어서 학습"이 오랫동안 목 데이터("3단계 자음과 모음 · 7 / 10 문항")를 보여주고, 누르면 진행 중이던 세션이 아니라 새 자음 세션을
 * 만들었다. 앱이 이어받을 세션의 식별자를 알 방법이 없었기 때문이다.
 *
 * <p>진행 중 세션은 사실상 하나뿐이다. {@code ProblemService.createSession}이 새 세션을 만들 때 같은 종류의 미완료 세션을 모두 완료
 * 처리하기 때문이다. 그래도 방어적으로 최신 것을 고른다.
 */
@Service
@RequiredArgsConstructor
public class InProgressSessionQueryService {

  private final LearningSessionRepository learningSessionRepository;
  private final ProblemSessionRepository problemSessionRepository;
  private final UserJpaRepository userJpaRepository;

  /** 커리큘럼 단계가 없는 세션은 복습이다 (`createSession(userId, null)`) */
  private static final String REVIEW_STAGE_NAME = "복습";

  @Transactional(readOnly = true)
  public Optional<InProgressSession> find(long userId) {
    User user =
        userJpaRepository
            .findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

    List<LearningSession> candidates = new ArrayList<>();
    candidates.addAll(
        learningSessionRepository.findAllByUserAndCurriculumStageIsNotNullAndCompletedAtIsNull(
            user));
    candidates.addAll(
        learningSessionRepository.findAllByUserAndCurriculumStageIsNullAndCompletedAtIsNull(user));

    return candidates.stream()
        .max(Comparator.comparing(LearningSession::getCreatedAt))
        .flatMap(this::toResumable);
  }

  /**
   * 이어서 풀 문항이 남아 있는 세션만 내보낸다.
   *
   * <p>배정 전에 실패해 문항이 없는 세션, 그리고 마지막 문항까지 풀고 완료 제출만 실패한 세션이 남을 수 있다. 둘 다 "이어서 학습"으로 보여주면 눌러도
   * 풀 것이 없다.
   */
  private Optional<InProgressSession> toResumable(LearningSession session) {
    List<ProblemSession> assigned =
        problemSessionRepository.findAllBySession_IdOrderBySequence(session.getId());
    long completed = assigned.stream().filter(ProblemSession::isCompleted).count();
    if (assigned.isEmpty() || completed >= assigned.size()) {
      return Optional.empty();
    }
    return Optional.of(
        new InProgressSession(
            session.getId(),
            session.getCurriculumStage() == null
                ? REVIEW_STAGE_NAME
                : session.getCurriculumStage().getName(),
            assigned.size(),
            Math.toIntExact(completed)));
  }

  public record InProgressSession(
      Long sessionId, String stageName, int totalCount, int completedCount) {}
}
