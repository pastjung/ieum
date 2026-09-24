package com.itda.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.learning.domain.AttemptCell;
import com.itda.learning.domain.AttemptCellRepository;
import com.itda.learning.domain.LearningSession;
import com.itda.learning.domain.LearningSessionRepository;
import com.itda.learning.domain.ProblemAttempt;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.learning.service.LearningSessionAnalysisService.AnalysisAttempt;
import com.itda.learning.service.LearningSessionAnalysisService.SessionSummaryResult;
import com.itda.learning.service.LearningSessionCompletionService.AttemptCommand;
import com.itda.learning.service.LearningSessionCompletionService.CompletionCommand;
import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.ProblemSession;
import com.itda.problem.domain.UserWrongProblem;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemSessionRepository;
import com.itda.problem.repository.UserWrongProblemRepository;
import com.itda.user.domain.User;
import com.itda.user.repository.UserJpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
  @Mock private UserWrongProblemRepository userWrongProblemRepository;
  @Mock private UserJpaRepository userJpaRepository;
  @Mock private LearningSessionAnalysisService analysisService;

  private LearningSessionCompletionService service;
  private LearningSession session;
  private ProblemSession problemSession;
  private ProblemAttempt storedAttempt;

  @BeforeEach
  void setUp() {
    service =
        new LearningSessionCompletionService(
            learningSessionRepository,
            problemSessionRepository,
            problemCellRepository,
            problemAttemptRepository,
            attemptCellRepository,
            userWrongProblemRepository,
            userJpaRepository,
            analysisService);

    session = new LearningSession(USER_ID, null);
    ReflectionTestUtils.setField(session, "id", SESSION_ID);
    Problem problem = new Problem(Level.WORD, "가방");
    ReflectionTestUtils.setField(problem, "id", PROBLEM_ID);
    problemSession = new ProblemSession(problem, session, 1L);
    ReflectionTestUtils.setField(problemSession, "id", PROBLEM_SESSION_ID);
    problemSession.complete();

    storedAttempt =
        ProblemAttempt.forFreeSession(
            USER_ID,
            PROBLEM_ID,
            SESSION_ID,
            1,
            null,
            false,
            LocalDateTime.of(2026, 9, 25, 10, 0));
    ReflectionTestUtils.setField(storedAttempt, "id", 100L);
    AttemptCell storedCell =
        new AttemptCell(
            storedAttempt, 1, 43, 32, LocalDateTime.of(2026, 9, 25, 10, 0));

    given(learningSessionRepository.findByIdForCompletion(SESSION_ID))
        .willReturn(Optional.of(session));
    given(problemSessionRepository.findAllForCompletion(SESSION_ID))
        .willReturn(List.of(problemSession));
    given(problemAttemptRepository.findAllByLearningSessionIdOrderBySessionItemNoAsc(SESSION_ID))
        .willReturn(List.of(storedAttempt));
    given(attemptCellRepository.findAllByAttemptIds(List.of(100L)))
        .willReturn(List.of(storedCell));
    given(problemCellRepository.findByProblemIdOrderByCellIndexAsc(PROBLEM_ID))
        .willReturn(List.of(new ProblemCell(problem, 1, 43)));
    given(userWrongProblemRepository.findAllByUser_IdAndProblem_IdIn(
            USER_ID, List.of(PROBLEM_ID)))
        .willReturn(List.of());
    given(userJpaRepository.getReferenceById(USER_ID))
        .willReturn(org.mockito.Mockito.mock(User.class));
    given(analysisService.summarizeSession(
            org.mockito.ArgumentMatchers.eq(USER_ID),
            org.mockito.ArgumentMatchers.anyList()))
        .willReturn(
            new SessionSummaryResult(
                "받침을 연습해 보세요.",
                "받침을 다시 연습해 보세요.",
                Map.of("dot_missing", 1L),
                List.of()));
  }

  @Test
  void 저장된_문제_풀이로_전체_요약을_요청하고_세션을_완료한다() {
    LearningSessionCompletionService.CompletionResult result =
        service.complete(USER_ID, SESSION_ID, command(List.of(32)));

    assertThat(result.totalProblemCount()).isEqualTo(1);
    assertThat(result.correctProblemCount()).isZero();
    assertThat(result.summary().errorTypeCounts()).containsEntry("dot_missing", 1L);
    assertThat(session.getCompletedAt()).isNotNull();
    verify(problemAttemptRepository, never()).saveAllAndFlush(org.mockito.ArgumentMatchers.anyList());
    verify(attemptCellRepository, never()).saveAllAndFlush(org.mockito.ArgumentMatchers.anyList());

    ArgumentCaptor<List<AnalysisAttempt>> attemptsCaptor = ArgumentCaptor.forClass(List.class);
    verify(analysisService).summarizeSession(
        org.mockito.ArgumentMatchers.eq(USER_ID), attemptsCaptor.capture());
    assertThat(attemptsCaptor.getValue()).singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.targetText()).isEqualTo("가방");
              assertThat(attempt.expectedPatterns()).containsExactly(43);
              assertThat(attempt.inputPatterns()).containsExactly(32);
            });
  }

  @Test
  void 오답_문제는_세션_완료_시점에_오답_목록에_추가한다() {
    service.complete(USER_ID, SESSION_ID, command(List.of(32)));

    ArgumentCaptor<List<UserWrongProblem>> wrongProblemCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(userWrongProblemRepository).saveAll(wrongProblemCaptor.capture());
    assertThat(wrongProblemCaptor.getValue()).singleElement()
        .satisfies(
            wrongProblem -> {
              assertThat(wrongProblem.getProblem().getId()).isEqualTo(PROBLEM_ID);
              assertThat(wrongProblem.isCompleted()).isFalse();
            });
  }

  @Test
  void 분석되지_않은_문제가_있으면_세션을_완료하지_않는다() {
    LearningSession incompleteSession = new LearningSession(USER_ID, null);
    ReflectionTestUtils.setField(incompleteSession, "id", SESSION_ID);
    ProblemSession incompleteProblem =
        new ProblemSession(problemSession.getProblem(), incompleteSession, 1L);
    ReflectionTestUtils.setField(incompleteProblem, "id", PROBLEM_SESSION_ID);
    given(learningSessionRepository.findByIdForCompletion(SESSION_ID))
        .willReturn(Optional.of(incompleteSession));
    given(problemSessionRepository.findAllForCompletion(SESSION_ID))
        .willReturn(List.of(incompleteProblem));

    assertThatThrownBy(() -> service.complete(USER_ID, SESSION_ID, command(List.of(32))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_COMPLETION_REQUEST));

    verify(analysisService, never())
        .summarizeSession(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void 전체_요약이_실패하면_세션과_오답_목록을_변경하지_않는다() {
    given(analysisService.summarizeSession(
            org.mockito.ArgumentMatchers.eq(USER_ID),
            org.mockito.ArgumentMatchers.anyList()))
        .willThrow(new BusinessException(ErrorCode.LEARNING_ANALYSIS_UNAVAILABLE));

    assertThatThrownBy(() -> service.complete(USER_ID, SESSION_ID, command(List.of(32))))
        .isInstanceOf(BusinessException.class);

    assertThat(session.getCompletedAt()).isNull();
    verify(userWrongProblemRepository, never())
        .saveAll(org.mockito.ArgumentMatchers.anyList());
    verify(learningSessionRepository, never()).saveAndFlush(session);
  }

  private static CompletionCommand command(List<Integer> inputPatterns) {
    return new CompletionCommand(
        List.of(new AttemptCommand(PROBLEM_SESSION_ID, inputPatterns)));
  }
}
