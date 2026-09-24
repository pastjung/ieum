package com.itda.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.itda.curriculum.domain.CurriculumStage;
import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.learning.domain.AttemptCell;
import com.itda.learning.domain.AttemptCellRepository;
import com.itda.learning.domain.LearningSession;
import com.itda.learning.domain.LearningSessionRepository;
import com.itda.learning.domain.ProblemAttempt;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.learning.service.LearningSessionCompletionService.AttemptCommand;
import com.itda.learning.service.LearningSessionCompletionService.CompletionCommand;
import com.itda.learning.service.LearningSessionCompletionService.CompletionResult;
import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.ProblemSession;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemSessionRepository;
import com.itda.user.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LearningSessionCompletionServiceTest {

  private static final long USER_ID = 1L;
  private static final long SESSION_ID = 10L;
  private static final long PROBLEM_ID = 20L;
  private static final long PROBLEM_SESSION_ID = 30L;
  @Mock private LearningSessionRepository learningSessionRepository;
  @Mock private ProblemSessionRepository problemSessionRepository;
  @Mock private ProblemCellRepository problemCellRepository;
  @Mock private ProblemAttemptRepository problemAttemptRepository;
  @Mock private AttemptCellRepository attemptCellRepository;

  private LearningSessionCompletionService service;
  private LearningSession session;
  private ProblemSession problemSession;
  private AtomicReference<List<ProblemAttempt>> savedAttempts;
  private AtomicReference<List<AttemptCell>> savedAttemptCells;

  @BeforeEach
  void setUp() {
    service =
        new LearningSessionCompletionService(
            learningSessionRepository,
            problemSessionRepository,
            problemCellRepository,
            problemAttemptRepository,
            attemptCellRepository);

    CurriculumStage curriculumStage = org.mockito.Mockito.mock(CurriculumStage.class);
    given(curriculumStage.getId()).willReturn(5L);
    User user = org.mockito.Mockito.mock(User.class);
    given(user.getId()).willReturn(USER_ID);
    session = new LearningSession(user, curriculumStage);
    ReflectionTestUtils.setField(session, "id", SESSION_ID);

    Problem problem = new Problem(Level.WORD, "가방");
    ReflectionTestUtils.setField(problem, "id", PROBLEM_ID);
    problemSession = new ProblemSession(problem, session, 1L);
    ReflectionTestUtils.setField(problemSession, "id", PROBLEM_SESSION_ID);

    given(learningSessionRepository.findByIdForCompletion(SESSION_ID))
        .willReturn(Optional.of(session));
    given(problemSessionRepository.findAllForCompletion(SESSION_ID))
        .willReturn(List.of(problemSession));
    given(problemCellRepository.findByProblemIdOrderByCellIndexAsc(PROBLEM_ID))
        .willReturn(List.of(new ProblemCell(problem, 1, 0b001011)));

    savedAttempts = new AtomicReference<>();
    given(problemAttemptRepository.saveAllAndFlush(org.mockito.ArgumentMatchers.anyList()))
        .willAnswer(
            invocation -> {
              List<ProblemAttempt> attempts = invocation.getArgument(0);
              for (int index = 0; index < attempts.size(); index++) {
                ReflectionTestUtils.setField(attempts.get(index), "id", 100L + index);
              }
              savedAttempts.set(attempts);
              return attempts;
            });
    savedAttemptCells = new AtomicReference<>();
    given(attemptCellRepository.saveAllAndFlush(org.mockito.ArgumentMatchers.anyList()))
        .willAnswer(
            invocation -> {
              List<AttemptCell> cells = invocation.getArgument(0);
              savedAttemptCells.set(cells);
              return cells;
            });
    given(learningSessionRepository.saveAndFlush(session)).willReturn(session);
  }

  @Test
  void 전체_답안을_서버에서_채점하고_누락점과_추가점을_반환한다() {
    Instant startedAt = Instant.now();
    CompletionResult result = service.complete(USER_ID, SESSION_ID, command(0b010101));

    assertThat(result.totalProblemCount()).isEqualTo(1);
    assertThat(result.correctProblemCount()).isZero();
    assertThat(result.completedAt()).isBetween(startedAt, Instant.now());
    assertThat(result.problemResults().getFirst().problemSessionId()).isEqualTo(PROBLEM_SESSION_ID);
    assertThat(result.problemResults().getFirst().attemptId()).isEqualTo(100L);
    assertThat(result.problemResults().getFirst().cells().getFirst().missingMask())
        .isEqualTo(0b001010);
    assertThat(result.problemResults().getFirst().cells().getFirst().extraMask())
        .isEqualTo(0b010100);
    assertThat(result.problemResults().getFirst().cells().getFirst().missingDots())
        .containsExactly(2, 4);
    assertThat(result.problemResults().getFirst().cells().getFirst().extraDots())
        .containsExactly(3, 5);
    assertThat(problemSession.isCompleted()).isTrue();
  }

  @Test
  void 완료된_세션을_재전송하면_기존_풀이를_반환한다() {
    CompletionCommand command = command(0b001011);
    CompletionResult first = service.complete(USER_ID, SESSION_ID, command);
    given(problemAttemptRepository.findAllByLearningSessionIdOrderBySessionItemNoAsc(SESSION_ID))
        .willReturn(savedAttempts.get());
    given(attemptCellRepository.findAllByAttemptIds(List.of(100L)))
        .willReturn(savedAttemptCells.get());

    CompletionResult retried = service.complete(USER_ID, SESSION_ID, command);

    assertThat(retried).isEqualTo(first);
    verify(problemAttemptRepository, times(1))
        .saveAllAndFlush(org.mockito.ArgumentMatchers.anyList());
    verify(attemptCellRepository, times(1)).saveAllAndFlush(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void 완료된_세션에_다른_답안을_보내도_최초_저장_결과를_반환한다() {
    CompletionResult first = service.complete(USER_ID, SESSION_ID, command(0b001011));
    given(problemAttemptRepository.findAllByLearningSessionIdOrderBySessionItemNoAsc(SESSION_ID))
        .willReturn(savedAttempts.get());
    given(attemptCellRepository.findAllByAttemptIds(List.of(100L)))
        .willReturn(savedAttemptCells.get());

    CompletionResult retried = service.complete(USER_ID, SESSION_ID, command(0b000000));

    assertThat(retried).isEqualTo(first);
    verify(problemAttemptRepository, times(1))
        .saveAllAndFlush(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void 커리큘럼_단계가_없는_복습_세션도_완료할_수_있다() {
    User user = org.mockito.Mockito.mock(User.class);
    given(user.getId()).willReturn(USER_ID);
    LearningSession reviewSession = new LearningSession(user, null);
    ReflectionTestUtils.setField(reviewSession, "id", SESSION_ID);
    given(learningSessionRepository.findByIdForCompletion(SESSION_ID))
        .willReturn(Optional.of(reviewSession));
    given(learningSessionRepository.saveAndFlush(reviewSession)).willReturn(reviewSession);

    service.complete(USER_ID, SESSION_ID, command(0b001011));

    assertThat(savedAttempts.get())
        .singleElement()
        .satisfies(attempt -> assertThat(attempt.getCurriculumStageId()).isNull());
  }

  @Test
  void 다른_사용자의_세션은_존재하지_않는_것처럼_응답한다() {
    assertThatThrownBy(() -> service.complete(USER_ID + 1, SESSION_ID, command(0)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.LEARNING_SESSION_NOT_FOUND));

    verify(problemAttemptRepository, never())
        .saveAllAndFlush(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void 배정되지_않은_problemSessionId는_거부한다() {
    CompletionCommand command =
        new CompletionCommand(List.of(new AttemptCommand(999L, List.of(0))));

    assertThatThrownBy(() -> service.complete(USER_ID, SESSION_ID, command))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_COMPLETION_REQUEST));
  }

  @Test
  void 정답_Cell_개수와_입력_개수가_다르면_거부한다() {
    CompletionCommand command =
        new CompletionCommand(List.of(new AttemptCommand(PROBLEM_SESSION_ID, List.of(1, 2))));

    assertThatThrownBy(() -> service.complete(USER_ID, SESSION_ID, command))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_COMPLETION_REQUEST));
  }

  private static CompletionCommand command(int inputPattern) {
    return new CompletionCommand(
        List.of(new AttemptCommand(PROBLEM_SESSION_ID, List.of(inputPattern))));
  }
}
