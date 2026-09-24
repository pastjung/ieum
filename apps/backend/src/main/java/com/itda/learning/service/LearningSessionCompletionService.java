package com.itda.learning.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.learning.domain.AttemptCell;
import com.itda.learning.domain.AttemptCellRepository;
import com.itda.learning.domain.LearningSession;
import com.itda.learning.domain.LearningSessionRepository;
import com.itda.learning.domain.ProblemAttempt;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.ProblemSession;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemSessionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LearningSessionCompletionService {

  private static final int MAX_PATTERN = 0b111111;
  private static final int DOTS_PER_CELL = 6;

  /** 입력하지 않은 칸·기대하지 않은 칸을 채우는 값. 앱의 {@code EMPTY_CELL}과 같다. */
  private static final int EMPTY_CELL = 0;

  private final LearningSessionRepository learningSessionRepository;
  private final ProblemSessionRepository problemSessionRepository;
  private final ProblemCellRepository problemCellRepository;
  private final ProblemAttemptRepository problemAttemptRepository;
  private final AttemptCellRepository attemptCellRepository;

  @Transactional
  public CompletionResult complete(long userId, long sessionId, CompletionCommand command) {
    validateCommand(command);

    LearningSession session =
        learningSessionRepository
            .findByIdForCompletion(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.LEARNING_SESSION_NOT_FOUND));

    if (!session.getUserId().equals(userId)) {
      throw new BusinessException(ErrorCode.LEARNING_SESSION_NOT_FOUND);
    }

    List<ProblemSession> assignedProblems =
        problemSessionRepository.findAllForCompletion(sessionId);

    if (session.getCompletedAt() != null) {
      return handleCompletedSession(session, assignedProblems, sessionId);
    }

    Map<Long, AttemptCommand> commandsByProblemSession =
        validateAndIndexAttempts(assignedProblems, command.attempts());
    Long curriculumStageId = curriculumStageId(session);
    LocalDateTime completedAt = LocalDateTime.now(ZoneOffset.UTC);

    List<ProblemAttempt> attempts = new ArrayList<>();
    List<AttemptCell> attemptCells = new ArrayList<>();
    for (int index = 0; index < assignedProblems.size(); index++) {
      ProblemSession assigned = assignedProblems.get(index);
      AttemptCommand attemptCommand = commandsByProblemSession.get(assigned.getId());
      List<ProblemCell> expectedCells =
          problemCellRepository.findByProblemIdOrderByCellIndexAsc(assigned.getProblem().getId());
      validateExpectedCells(expectedCells);

      boolean correct = isCorrect(expectedCells, attemptCommand.inputPatterns());
      ProblemAttempt attempt =
          ProblemAttempt.forFreeSession(
              userId,
              assigned.getProblem().getId(),
              sessionId,
              index + 1,
              curriculumStageId,
              correct,
              completedAt);
      attempts.add(attempt);
      attemptCells.addAll(createAttemptCells(attempt, expectedCells, attemptCommand, completedAt));
      assigned.complete();
    }

    List<ProblemAttempt> savedAttempts = problemAttemptRepository.saveAllAndFlush(attempts);
    List<AttemptCell> savedAttemptCells = attemptCellRepository.saveAllAndFlush(attemptCells);
    session.complete(completedAt);
    learningSessionRepository.saveAndFlush(session);

    return toResult(session, assignedProblems, savedAttempts, savedAttemptCells);
  }

  private CompletionResult handleCompletedSession(
      LearningSession session, List<ProblemSession> assignedProblems, long sessionId) {
    List<ProblemAttempt> storedAttempts =
        problemAttemptRepository.findAllByLearningSessionIdOrderBySessionItemNoAsc(sessionId);
    List<AttemptCell> storedAttemptCells = findAttemptCells(storedAttempts);

    return toResult(session, assignedProblems, storedAttempts, storedAttemptCells);
  }

  private List<AttemptCell> findAttemptCells(List<ProblemAttempt> attempts) {
    List<Long> attemptIds = attempts.stream().map(ProblemAttempt::getId).toList();
    if (attemptIds.isEmpty()) {
      return List.of();
    }
    return attemptCellRepository.findAllByAttemptIds(attemptIds);
  }

  private static void validateCommand(CompletionCommand command) {
    if (command == null || command.attempts() == null || command.attempts().isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_COMPLETION_REQUEST);
    }
    for (AttemptCommand attempt : command.attempts()) {
      if (attempt == null
          || attempt.problemSessionId() == null
          || attempt.problemSessionId() <= 0
          || attempt.inputPatterns() == null
          || attempt.inputPatterns().isEmpty()) {
        throw new BusinessException(ErrorCode.INVALID_COMPLETION_REQUEST);
      }
      boolean invalidPattern =
          attempt.inputPatterns().stream()
              .anyMatch(pattern -> pattern == null || pattern < 0 || pattern > MAX_PATTERN);
      if (invalidPattern) {
        throw new BusinessException(ErrorCode.INVALID_COMPLETION_REQUEST);
      }
    }
  }

  private static Map<Long, AttemptCommand> validateAndIndexAttempts(
      List<ProblemSession> assignedProblems, List<AttemptCommand> commands) {
    if (assignedProblems.isEmpty()
        || assignedProblems.size() != commands.size()
        || assignedProblems.stream().anyMatch(ProblemSession::isCompleted)) {
      throw new BusinessException(ErrorCode.INVALID_COMPLETION_REQUEST);
    }

    Map<Long, AttemptCommand> indexed = new HashMap<>();
    for (AttemptCommand command : commands) {
      if (indexed.put(command.problemSessionId(), command) != null) {
        throw new BusinessException(ErrorCode.INVALID_COMPLETION_REQUEST);
      }
    }

    Set<Long> assignedIds =
        assignedProblems.stream().map(ProblemSession::getId).collect(Collectors.toSet());
    if (!indexed.keySet().equals(assignedIds)) {
      throw new BusinessException(ErrorCode.INVALID_COMPLETION_REQUEST);
    }
    return indexed;
  }

  /**
   * 문제 쪽 데이터가 성립하는지만 본다. 답안의 칸 수는 여기서 보지 않는다 — 기대와 다른 칸 수는 요청의 결함이 아니라 학습자의 오답이고, {@link
   * #isCorrect}가 오답으로 판정한다. 예전에는 길이가 다르면 요청 전체를 거부했는데, {@code complete()}가 {@code @Transactional}이라
   * 12문항 중 한 문항의 칸 수만 어긋나도 세션 전체가 롤백됐다.
   */
  private static void validateExpectedCells(List<ProblemCell> expectedCells) {
    if (expectedCells.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_COMPLETION_REQUEST);
    }
    for (int index = 0; index < expectedCells.size(); index++) {
      if (expectedCells.get(index).getCellIndex() != index + 1) {
        throw new IllegalStateException("문제의 셀 순서가 연속적이지 않습니다.");
      }
    }
  }

  /**
   * 앱의 {@code gradeAnswer}(front/packages/domain)와 같은 규칙으로 판정한다: 칸 수가 기대와 다르면 오답이고(모자라도, 넘쳐도), 같을
   * 때만 칸별 패턴을 비교한다. 두 채점이 갈라지면 사용자는 화면에서 들은 결과와 결과 화면의 점수가 다른 것을 보게 된다.
   */
  private static boolean isCorrect(List<ProblemCell> expectedCells, List<Integer> inputPatterns) {
    if (expectedCells.size() != inputPatterns.size()) {
      return false;
    }
    for (int index = 0; index < expectedCells.size(); index++) {
      if (expectedCells.get(index).getExpectedPattern() != inputPatterns.get(index)) {
        return false;
      }
    }
    return true;
  }

  /**
   * 기대 칸과 입력 칸 중 긴 쪽만큼 셀을 만들고, 없는 쪽은 빈 셀({@code 0})로 둔다. 그래야 응답의 {@code cells}가 모자란 칸·넘친 칸을 모두 담아
   * 앱이 "몇 번째 칸에 무엇을 넣었고 무엇이 기대됐는지"로 피드백을 만들 수 있다.
   */
  private static List<AttemptCell> createAttemptCells(
      ProblemAttempt attempt,
      List<ProblemCell> expectedCells,
      AttemptCommand attemptCommand,
      LocalDateTime respondedAt) {
    List<Integer> inputPatterns = attemptCommand.inputPatterns();
    int cellCount = Math.max(expectedCells.size(), inputPatterns.size());
    List<AttemptCell> cells = new ArrayList<>(cellCount);
    for (int index = 0; index < cellCount; index++) {
      int expectedPattern =
          index < expectedCells.size() ? expectedCells.get(index).getExpectedPattern() : EMPTY_CELL;
      int inputPattern = index < inputPatterns.size() ? inputPatterns.get(index) : EMPTY_CELL;
      cells.add(new AttemptCell(attempt, index + 1, expectedPattern, inputPattern, respondedAt));
    }
    return cells;
  }

  private static Long curriculumStageId(LearningSession session) {
    if (session.getCurriculumStage() == null) {
      return null;
    }
    Long curriculumStageId = session.getCurriculumStage().getId();
    if (curriculumStageId == null) {
      throw new IllegalStateException("학습 세션의 커리큘럼 단계 ID가 없습니다.");
    }
    return curriculumStageId;
  }

  private static CompletionResult toResult(
      LearningSession session,
      List<ProblemSession> assignedProblems,
      List<ProblemAttempt> attempts,
      List<AttemptCell> attemptCells) {
    Map<Long, ProblemAttempt> attemptsByProblemId =
        attempts.stream()
            .collect(
                Collectors.toMap(
                    ProblemAttempt::getProblemId, Function.identity(), (first, second) -> first));
    Map<Long, List<AttemptCell>> cellsByAttemptId =
        attemptCells.stream().collect(Collectors.groupingBy(cell -> cell.getAttempt().getId()));

    if (attemptsByProblemId.size() != assignedProblems.size()) {
      throw new IllegalStateException("저장된 시도와 배정 문제의 개수가 일치하지 않습니다.");
    }

    List<ProblemResult> problemResults = new ArrayList<>();
    for (ProblemSession assigned : assignedProblems) {
      ProblemAttempt attempt = attemptsByProblemId.get(assigned.getProblem().getId());
      if (attempt == null) {
        throw new IllegalStateException("배정 문제에 대응하는 저장된 시도가 없습니다.");
      }
      List<AttemptCell> cells = cellsByAttemptId.getOrDefault(attempt.getId(), List.of());
      if (cells.isEmpty()) {
        throw new IllegalStateException("저장된 시도에 셀 결과가 없습니다.");
      }
      problemResults.add(toProblemResult(assigned, attempt, cells));
    }

    int correctProblemCount = (int) problemResults.stream().filter(ProblemResult::correct).count();
    Instant completedAt = session.getCompletedAt().toInstant(ZoneOffset.UTC);

    return new CompletionResult(
        session.getId(), completedAt, problemResults.size(), correctProblemCount, problemResults);
  }

  private static ProblemResult toProblemResult(
      ProblemSession assigned, ProblemAttempt attempt, List<AttemptCell> attemptCells) {
    List<CellResult> cells =
        attemptCells.stream().map(LearningSessionCompletionService::toCellResult).toList();
    return new ProblemResult(
        assigned.getId(),
        assigned.getProblem().getId(),
        attempt.getId(),
        attempt.isCorrect(),
        cells);
  }

  private static CellResult toCellResult(AttemptCell cell) {
    int expectedPattern = cell.getExpectedPattern();
    int inputPattern = cell.getInputPattern();
    int missingMask = expectedPattern & ~inputPattern & MAX_PATTERN;
    int extraMask = inputPattern & ~expectedPattern & MAX_PATTERN;
    return new CellResult(
        cell.getCellIndex(),
        expectedPattern,
        inputPattern,
        cell.getDiffMask(),
        missingMask,
        extraMask,
        dotNumbers(missingMask),
        dotNumbers(extraMask));
  }

  private static List<Integer> dotNumbers(int mask) {
    List<Integer> dots = new ArrayList<>();
    for (int dot = 1; dot <= DOTS_PER_CELL; dot++) {
      if ((mask & (1 << (dot - 1))) != 0) {
        dots.add(dot);
      }
    }
    return List.copyOf(dots);
  }

  public record CompletionCommand(List<AttemptCommand> attempts) {
    public CompletionCommand {
      attempts = attempts == null ? null : List.copyOf(attempts);
    }
  }

  public record AttemptCommand(Long problemSessionId, List<Integer> inputPatterns) {
    public AttemptCommand {
      inputPatterns = inputPatterns == null ? null : List.copyOf(inputPatterns);
    }
  }

  public record CompletionResult(
      Long sessionId,
      Instant completedAt,
      int totalProblemCount,
      int correctProblemCount,
      List<ProblemResult> problemResults) {
    public CompletionResult {
      problemResults = List.copyOf(problemResults);
    }
  }

  public record ProblemResult(
      Long problemSessionId,
      Long problemId,
      Long attemptId,
      boolean correct,
      List<CellResult> cells) {
    public ProblemResult {
      cells = List.copyOf(cells);
    }
  }

  public record CellResult(
      int cellIndex,
      int expectedPattern,
      int inputPattern,
      int diffMask,
      int missingMask,
      int extraMask,
      List<Integer> missingDots,
      List<Integer> extraDots) {
    public CellResult {
      missingDots = List.copyOf(missingDots);
      extraDots = List.copyOf(extraDots);
    }
  }
}
