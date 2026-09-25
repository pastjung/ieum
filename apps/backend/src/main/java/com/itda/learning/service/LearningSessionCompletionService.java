package com.itda.learning.service;

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
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.ProblemSession;
import com.itda.problem.domain.UserWrongProblem;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemSessionRepository;
import com.itda.problem.repository.UserWrongProblemRepository;
import com.itda.user.domain.User;
import com.itda.user.repository.UserJpaRepository;
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
  private final UserWrongProblemRepository userWrongProblemRepository;
  private final UserJpaRepository userJpaRepository;
  private final LearningSessionAnalysisService analysisService;

  @Transactional
  public CompletionResult complete(long userId, long sessionId, CompletionCommand command) {
    validateCommand(command);

    LearningSession session = findOwnedSession(userId, sessionId);

    List<ProblemSession> assignedProblems =
        problemSessionRepository.findAllForCompletion(sessionId);
    if (session.getCompletedAt() != null) {
      return handleCompletedSession(session, assignedProblems, sessionId);
    }

    Map<Long, AttemptCommand> commandsByProblemSession =
        validateAndIndexAttempts(assignedProblems, command.attempts());
    List<ProblemAttempt> storedAttempts =
        problemAttemptRepository.findAllByLearningSessionIdOrderBySessionItemNoAsc(sessionId);
    validateStoredAttempts(assignedProblems, storedAttempts);
    List<AttemptCell> storedAttemptCells = findAttemptCells(storedAttempts);

    List<AnalysisAttempt> analysisAttempts =
        prepareAnalysisAttempts(assignedProblems, commandsByProblemSession);
    // 요약도 같은 벽에 막힌다 — 낱자 문항이 섞이면 FastAPI가 세션 전체를 422로 거절해
    // 완료 제출이 503이 된다. 하나라도 AI가 못 받는 문항이 있으면 직접 집계한다
    // (부분 요약은 만들 수 없다 — AI는 어절 전체를 함께 보고 규칙을 고른다)
    boolean aiGradable =
        analysisAttempts.stream()
            .allMatch(attempt -> LearningSessionAnalysisService.isAiGradable(attempt.targetText()));
    SessionSummaryResult summary =
        aiGradable
            ? analysisService.summarizeSession(userId, analysisAttempts)
            : SessionSummaryResult.errorCountsOnly(
                AttemptErrorClassifier.merge(
                    analysisAttempts.stream()
                        .map(
                            attempt ->
                                AttemptErrorClassifier.classify(
                                    attempt.expectedPatterns(), attempt.inputPatterns()))
                        .toList()));

    updateWrongProblems(userId, assignedProblems, storedAttempts);
    LocalDateTime completedAt = LocalDateTime.now(ZoneOffset.UTC);
    session.complete(completedAt);
    learningSessionRepository.saveAndFlush(session);
    return toResult(session, assignedProblems, storedAttempts, storedAttemptCells, summary);
  }

  private LearningSession findOwnedSession(long userId, long sessionId) {
    LearningSession session =
        learningSessionRepository
            .findByIdForCompletion(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.LEARNING_SESSION_NOT_FOUND));

    if (!session.getUser().getId().equals(userId)) {
      throw new BusinessException(ErrorCode.LEARNING_SESSION_NOT_FOUND);
    }
    return session;
  }

  private List<AnalysisAttempt> prepareAnalysisAttempts(
      List<ProblemSession> assignedProblems, Map<Long, AttemptCommand> commandsByProblemSession) {
    List<AnalysisAttempt> attempts = new ArrayList<>();
    for (ProblemSession assigned : assignedProblems) {
      AttemptCommand command = commandsByProblemSession.get(assigned.getId());
      List<ProblemCell> expectedCells =
          problemCellRepository.findByProblemIdOrderByCellIndexAsc(assigned.getProblem().getId());
      validateExpectedCells(expectedCells);
      attempts.add(
          new AnalysisAttempt(
              assigned.getProblem().getId(),
              assigned.getProblem().getTargetText(),
              expectedCells.stream().map(ProblemCell::getExpectedPattern).toList(),
              command.inputPatterns()));
    }
    return List.copyOf(attempts);
  }

  private void updateWrongProblems(
      long userId, List<ProblemSession> assignedProblems, List<ProblemAttempt> attempts) {
    List<Long> problemIds =
        assignedProblems.stream().map(assigned -> assigned.getProblem().getId()).toList();
    Map<Long, UserWrongProblem> wrongProblemsByProblemId =
        userWrongProblemRepository.findAllByUser_IdAndProblem_IdIn(userId, problemIds).stream()
            .collect(
                Collectors.toMap(
                    wrongProblem -> wrongProblem.getProblem().getId(), Function.identity()));
    Map<Long, ProblemAttempt> attemptsByProblemId =
        attempts.stream()
            .collect(Collectors.toMap(ProblemAttempt::getProblemId, Function.identity()));

    User userReference = null;
    List<UserWrongProblem> newWrongProblems = new ArrayList<>();
    for (ProblemSession assigned : assignedProblems) {
      Long problemId = assigned.getProblem().getId();
      ProblemAttempt attempt = attemptsByProblemId.get(problemId);
      if (attempt == null) {
        throw new IllegalStateException("배정 문제에 대응하는 풀이 시도가 없습니다.");
      }

      UserWrongProblem existingWrongProblem = wrongProblemsByProblemId.get(problemId);
      if (attempt.isCorrect()) {
        if (existingWrongProblem != null) {
          existingWrongProblem.complete();
        }
      } else if (existingWrongProblem == null) {
        if (userReference == null) {
          userReference = userJpaRepository.getReferenceById(userId);
        }
        newWrongProblems.add(
            new UserWrongProblem(
                userReference, assigned.getProblem(), assigned.getProblem().getLevel()));
      }
    }

    if (!newWrongProblems.isEmpty()) {
      userWrongProblemRepository.saveAll(newWrongProblems);
    }
  }

  private CompletionResult handleCompletedSession(
      LearningSession session, List<ProblemSession> assignedProblems, long sessionId) {
    List<ProblemAttempt> storedAttempts =
        problemAttemptRepository.findAllByLearningSessionIdOrderBySessionItemNoAsc(sessionId);
    validateStoredAttempts(assignedProblems, storedAttempts);
    return toResult(
        session,
        assignedProblems,
        storedAttempts,
        findAttemptCells(storedAttempts),
        SessionSummaryResult.notAvailable());
  }

  private List<AttemptCell> findAttemptCells(List<ProblemAttempt> attempts) {
    List<Long> attemptIds = attempts.stream().map(ProblemAttempt::getId).toList();
    if (attemptIds.isEmpty()) {
      return List.of();
    }
    return attemptCellRepository.findAllByAttemptIds(attemptIds);
  }

  private static void validateStoredAttempts(
      List<ProblemSession> assignedProblems, List<ProblemAttempt> attempts) {
    Set<Long> assignedProblemIds =
        assignedProblems.stream()
            .map(assigned -> assigned.getProblem().getId())
            .collect(Collectors.toSet());
    Set<Long> attemptedProblemIds =
        attempts.stream().map(ProblemAttempt::getProblemId).collect(Collectors.toSet());
    if (attempts.size() != assignedProblems.size()
        || !attemptedProblemIds.equals(assignedProblemIds)) {
      throw new BusinessException(ErrorCode.INVALID_COMPLETION_REQUEST);
    }
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
          || attempt.inputPatterns().stream()
              .anyMatch(pattern -> pattern == null || pattern < 0 || pattern > MAX_PATTERN)) {
        throw new BusinessException(ErrorCode.INVALID_COMPLETION_REQUEST);
      }
    }
  }

  private static Map<Long, AttemptCommand> validateAndIndexAttempts(
      List<ProblemSession> assignedProblems, List<AttemptCommand> commands) {
    if (assignedProblems.isEmpty()
        || assignedProblems.size() != commands.size()
        || assignedProblems.stream().anyMatch(assigned -> !assigned.isCompleted())) {
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

  private static void validateExpectedCells(List<ProblemCell> expectedCells) {
    if (expectedCells.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_COMPLETION_REQUEST);
    }
    for (int index = 0; index < expectedCells.size(); index++) {
      if (expectedCells.get(index).getCellIndex() != index + 1) {
        throw new IllegalStateException("문제의 정답 셀 순서가 연속적이지 않습니다.");
      }
    }
  }

  private static CompletionResult toResult(
      LearningSession session,
      List<ProblemSession> assignedProblems,
      List<ProblemAttempt> attempts,
      List<AttemptCell> attemptCells,
      SessionSummaryResult summary) {
    Map<Long, ProblemAttempt> attemptsByProblemId =
        attempts.stream()
            .collect(
                Collectors.toMap(
                    ProblemAttempt::getProblemId, Function.identity(), (first, second) -> first));
    Map<Long, List<AttemptCell>> cellsByAttemptId =
        attemptCells.stream().collect(Collectors.groupingBy(cell -> cell.getAttempt().getId()));

    List<ProblemResult> problemResults = new ArrayList<>();
    for (ProblemSession assigned : assignedProblems) {
      ProblemAttempt attempt = attemptsByProblemId.get(assigned.getProblem().getId());
      if (attempt == null) {
        throw new IllegalStateException("배정 문제에 대응하는 저장된 풀이 시도가 없습니다.");
      }
      List<AttemptCell> cells = cellsByAttemptId.getOrDefault(attempt.getId(), List.of());
      if (cells.isEmpty()) {
        throw new IllegalStateException("저장된 풀이 시도의 셀 결과가 없습니다.");
      }
      problemResults.add(toProblemResult(assigned, attempt, cells));
    }

    int correctProblemCount = (int) problemResults.stream().filter(ProblemResult::correct).count();
    Instant completedAt = session.getCompletedAt().toInstant(ZoneOffset.UTC);
    return new CompletionResult(
        session.getId(),
        completedAt,
        problemResults.size(),
        correctProblemCount,
        problemResults,
        summary);
  }

  private static ProblemResult toProblemResult(
      ProblemSession assigned, ProblemAttempt attempt, List<AttemptCell> attemptCells) {
    return new ProblemResult(
        assigned.getId(),
        assigned.getProblem().getId(),
        attempt.getId(),
        attempt.isCorrect(),
        attemptCells.stream().map(LearningSessionCompletionService::toCellResult).toList());
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
      List<ProblemResult> problemResults,
      SessionSummaryResult summary) {
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
