package com.itda.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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
import com.itda.learning.service.LearningSessionCompletionService.CellResult;
import com.itda.learning.service.LearningSessionCompletionService.CompletionCommand;
import com.itda.learning.service.LearningSessionCompletionService.CompletionResult;
import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.ProblemSession;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemSessionRepository;
import java.time.Instant;
import java.util.Arrays;
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
  private static final int FIRST_CELL = 0b001011;
  private static final int SECOND_CELL = 0b000001;
  private static final int THIRD_CELL = 0b000100;

  @Mock private LearningSessionRepository learningSessionRepository;
  @Mock private ProblemSessionRepository problemSessionRepository;
  @Mock private ProblemCellRepository problemCellRepository;
  @Mock private ProblemAttemptRepository problemAttemptRepository;
  @Mock private AttemptCellRepository attemptCellRepository;

  private LearningSessionCompletionService service;
  private LearningSession session;
  private Problem problem;
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
    session = new LearningSession(USER_ID, curriculumStage);
    ReflectionTestUtils.setField(session, "id", SESSION_ID);

    problem = new Problem(Level.WORD, "가방");
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
    LearningSession reviewSession = new LearningSession(USER_ID, null);
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
  void 기대보다_입력_칸이_모자라면_거부하지_않고_오답으로_처리한다() {
    givenTwoCellProblem();

    CompletionResult result = service.complete(USER_ID, SESSION_ID, command(FIRST_CELL));

    assertThat(result.correctProblemCount()).isZero();
    assertThat(result.problemResults().getFirst().correct()).isFalse();
    // 입력하지 않은 둘째 칸도 결과에 담긴다 — 앱이 "둘째 칸이 비었다"를 말할 수 있어야 한다
    assertThat(result.problemResults().getFirst().cells())
        .extracting(CellResult::cellIndex, CellResult::expectedPattern, CellResult::inputPattern)
        .containsExactly(tuple(1, FIRST_CELL, FIRST_CELL), tuple(2, SECOND_CELL, 0));
    assertThat(problemSession.isCompleted()).isTrue();
  }

  @Test
  void 기대보다_입력_칸이_넘치면_거부하지_않고_오답으로_처리한다() {
    givenTwoCellProblem();

    CompletionResult result =
        service.complete(USER_ID, SESSION_ID, command(FIRST_CELL, SECOND_CELL, THIRD_CELL));

    assertThat(result.correctProblemCount()).isZero();
    assertThat(result.problemResults().getFirst().correct()).isFalse();
    // 넘친 셋째 칸도 결과에 담긴다 — 기대는 빈 칸, 입력은 사용자가 넣은 값
    assertThat(result.problemResults().getFirst().cells())
        .extracting(CellResult::cellIndex, CellResult::expectedPattern, CellResult::inputPattern)
        .containsExactly(
            tuple(1, FIRST_CELL, FIRST_CELL),
            tuple(2, SECOND_CELL, SECOND_CELL),
            tuple(3, 0, THIRD_CELL));
  }

  /**
   * 서버 채점이 앱의 `gradeAnswer`(front/packages/domain)와 같은 판정을 내리는지 고정한다. 같은 문항(기대 2칸)·같은 입력 세 가지에 대한
   * 기대 판정은 front 쪽 `sessionStore.test.ts`의 "로컬 채점과 서버 채점이 같은 판정을 내린다"와 짝을 이룬다. 갈라지면 사용자는 문항마다 들은
   * 결과와 결과 화면의 점수가 다른 것을 보게 된다.
   */
  @Test
  void 칸_수가_부족_정확_초과인_세_경우의_판정이_로컬_채점과_같다() {
    assertThat(serverVerdict(FIRST_CELL)).isFalse();
    assertThat(serverVerdict(FIRST_CELL, SECOND_CELL)).isTrue();
    assertThat(serverVerdict(FIRST_CELL, SECOND_CELL, THIRD_CELL)).isFalse();
  }

  private boolean serverVerdict(int... inputPatterns) {
    LearningSession freshSession = new LearningSession(USER_ID, null);
    ReflectionTestUtils.setField(freshSession, "id", SESSION_ID);
    ProblemSession freshProblemSession = new ProblemSession(problem, freshSession, 1L);
    ReflectionTestUtils.setField(freshProblemSession, "id", PROBLEM_SESSION_ID);
    given(learningSessionRepository.findByIdForCompletion(SESSION_ID))
        .willReturn(Optional.of(freshSession));
    given(problemSessionRepository.findAllForCompletion(SESSION_ID))
        .willReturn(List.of(freshProblemSession));
    given(learningSessionRepository.saveAndFlush(freshSession)).willReturn(freshSession);
    givenTwoCellProblem();

    return service
        .complete(USER_ID, SESSION_ID, command(inputPatterns))
        .problemResults()
        .getFirst()
        .correct();
  }

  private void givenTwoCellProblem() {
    given(problemCellRepository.findByProblemIdOrderByCellIndexAsc(PROBLEM_ID))
        .willReturn(
            List.of(
                new ProblemCell(problem, 1, FIRST_CELL), new ProblemCell(problem, 2, SECOND_CELL)));
  }

  private static CompletionCommand command(int... inputPatterns) {
    List<Integer> patterns = Arrays.stream(inputPatterns).boxed().toList();
    return new CompletionCommand(List.of(new AttemptCommand(PROBLEM_SESSION_ID, patterns)));
  }
}
