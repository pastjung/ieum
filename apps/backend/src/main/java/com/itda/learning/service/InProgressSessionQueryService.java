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
 * <p>홈 화면의 "이어서 학습"이 오랫동안 목 데이터("3단계 자음과 모음 · 7 / 10 문항")를 보여주고, 누르면 진행 중이던 세션이 아니라 새 자음 세션을 만들었다.
 * 앱이 이어받을 세션의 식별자를 알 방법이 없었기 때문이다.
 *
 * <p>진행 중 세션은 사실상 하나뿐이다. {@code ProblemService.createSession}이 새 세션을 만들 때 같은 종류의 미완료 세션을 모두 완료 처리하기
 * 때문이다. 그래도 방어적으로 최신 것을 고른다.
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
   * 아직 끝나지 않은 세션.
   *
   * <p>문항이 하나도 배정되지 않은 세션만 뺀다 — 배정 전에 실패한 잔재라 이어갈 것이 없다.
   *
   * <p><b>모두 푼 세션은 뺀다고 했다가 되돌렸다.</b> 마지막 문항까지 풀고 완료 제출만 실패하면 그 세션이 목록에서 사라져, 학습자가 푼 12문항이 영영 결과를 보지
   * 못하고 갇힌다. 풀 문항이 남았는지는 {@code completedCount}와 {@code totalCount}를 견주면 호출부가 알 수 있다.
   */
  private Optional<InProgressSession> toResumable(LearningSession session) {
    List<ProblemSession> assigned =
        problemSessionRepository.findAllBySession_IdOrderBySequence(session.getId());
    long completed = assigned.stream().filter(ProblemSession::isCompleted).count();
    if (assigned.isEmpty()) {
      return Optional.empty();
    }
    boolean review = session.getCurriculumStage() == null;
    return Optional.of(
        new InProgressSession(
            session.getId(),
            review ? REVIEW_STAGE_NAME : session.getCurriculumStage().getName(),
            review,
            assigned.size(),
            Math.toIntExact(completed)));
  }

  /**
   * @param review 복습 세션인지. 새 세션을 만들면 <b>같은 종류의</b> 미완료 세션만 닫히므로({@code
   *     ProblemService.createSession}), 앱이 "이 학습을 시작하면 진행 중인 것이 닫힌다"를 판단하려면 표시용 이름이 아니라 이 값을 봐야 한다
   */
  public record InProgressSession(
      Long sessionId, String stageName, boolean review, int totalCount, int completedCount) {}
}
