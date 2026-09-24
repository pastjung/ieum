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

    if (!session.getUser().getId().equals(userId)) {
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
      validateCellInputs(expectedCells, attemptCommand.inputPatterns());

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

  private static void validateCellInputs(
      List<ProblemCell> expectedCells, List<Integer> inputPatterns) {
    if (expectedCells.isEmpty() || expectedCells.size() != inputPatterns.size()) {
      throw new BusinessException(ErrorCode.INVALID_COMPLETION_REQUEST);
    }
    for (int index = 0; index < expectedCells.size(); index++) {
      if (expectedCells.get(index).getCellIndex() != index + 1) {
        throw new IllegalStateException("문제의 셀 순서가 연속적이지 않습니다.");
      }
    }
  }

  private static boolean isCorrect(List<ProblemCell> expectedCells, List<Integer> inputPatterns) {
    for (int index = 0; index < expectedCells.size(); index++) {
      if (expectedCells.get(index).getExpectedPattern() != inputPatterns.get(index)) {
        return false;
      }
    }
    return true;
  }

  private static List<AttemptCell> createAttemptCells(
      ProblemAttempt attempt,
      List<ProblemCell> expectedCells,
      AttemptCommand attemptCommand,
      LocalDateTime respondedAt) {
    List<AttemptCell> cells = new ArrayList<>(expectedCells.size());
    for (int index = 0; index < expectedCells.size(); index++) {
      cells.add(
          new AttemptCell(
              attempt,
              index + 1,
              expectedCells.get(index).getExpectedPattern(),
              attemptCommand.inputPatterns().get(index),
              respondedAt));
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
