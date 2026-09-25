package com.itda.learning.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.learning.domain.AttemptCell;
import com.itda.learning.domain.AttemptCellRepository;
import com.itda.learning.domain.AttemptSkillResult;
import com.itda.learning.domain.AttemptSkillResultError;
import com.itda.learning.domain.AttemptSkillResultErrorRepository;
import com.itda.learning.domain.AttemptSkillResultRepository;
import com.itda.learning.domain.LearningSession;
import com.itda.learning.domain.LearningSessionRepository;
import com.itda.learning.domain.ProblemAttempt;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.learning.service.LearningSessionAnalysisService.AnalysisAttempt;
import com.itda.learning.service.LearningSessionAnalysisService.AttemptAnalysisResult;
import com.itda.learning.service.LearningSessionAnalysisService.FeedbackResult;
import com.itda.problem.domain.ErrorType;
import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.ProblemSession;
import com.itda.problem.repository.LearningSkillRepository;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemSessionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
public class LearningAttemptService {

  private static final int MAX_PATTERN = 0b111111;
  private static final int DOTS_PER_CELL = 6;

  private final LearningSessionRepository learningSessionRepository;
  private final ProblemSessionRepository problemSessionRepository;
  private final ProblemCellRepository problemCellRepository;
  private final ProblemAttemptRepository problemAttemptRepository;
  private final AttemptCellRepository attemptCellRepository;
  private final AttemptSkillResultRepository attemptSkillResultRepository;
  private final AttemptSkillResultErrorRepository attemptSkillResultErrorRepository;
  private final LearningSkillRepository learningSkillRepository;
  private final LearningSessionAnalysisService analysisService;

  @Transactional
  public AttemptResult analyze(long userId, long sessionId, AttemptCommand command) {
    validateCommand(command);
    LearningSession session = findOwnedSession(userId, sessionId);
    if (session.getCompletedAt() != null) {
      throw new BusinessException(ErrorCode.LEARNING_PROBLEM_ALREADY_ANALYZED);
    }

    ProblemSession assigned = findAssignedProblem(sessionId, command.problemSessionId());
    if (assigned.isCompleted()) {
      throw new BusinessException(ErrorCode.LEARNING_PROBLEM_ALREADY_ANALYZED);
    }
    List<ProblemCell> expectedCells =
        problemCellRepository.findByProblemIdOrderByCellIndexAsc(assigned.getProblem().getId());
    validateExpectedCells(expectedCells);

    LocalDateTime attemptedAt = LocalDateTime.now(ZoneOffset.UTC);
    AttemptAnalysisResult analysis =
        analysisService.analyzeAttempt(
            userId,
            new AnalysisAttempt(
                assigned.getProblem().getId(),
                assigned.getProblem().getTargetText(),
                expectedCells.stream().map(ProblemCell::getExpectedPattern).toList(),
                command.inputPatterns()),
            attemptedAt);

    boolean correct = isCorrect(expectedCells, command.inputPatterns());
    ProblemAttempt attempt =
        ProblemAttempt.forFreeSession(
            userId,
            assigned.getProblem().getId(),
            sessionId,
            Math.toIntExact(assigned.getSequence()),
            curriculumStageId(session),
            correct,
            attemptedAt);
    ProblemAttempt savedAttempt = problemAttemptRepository.saveAndFlush(attempt);
    List<AttemptCell> savedCells =
        attemptCellRepository.saveAllAndFlush(
            createAttemptCells(savedAttempt, expectedCells, command.inputPatterns(), attemptedAt));
    List<ViolatedSkill> violatedSkills = saveSkillResults(savedAttempt, analysis);

    assigned.complete();
    problemSessionRepository.saveAndFlush(assigned);
    return new AttemptResult(
        assigned.getId(),
        assigned.getProblem().getId(),
        savedAttempt.getId(),
        correct,
        attemptedAt.toInstant(ZoneOffset.UTC),
        savedCells.stream().map(LearningAttemptService::toCellResult).toList(),
        analysis.feedback(),
        violatedSkills);
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

  private ProblemSession findAssignedProblem(long sessionId, long problemSessionId) {
    return problemSessionRepository.findAllForCompletion(sessionId).stream()
        .filter(assigned -> assigned.getId().equals(problemSessionId))
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ATTEMPT_ANALYSIS_REQUEST));
  }

  /**
   * 요소별 정오를 저장하고, **어긴 학습요소**를 돌려준다.
   *
   * AI는 어긴 요소의 id만 알려준다. 사람이 읽을 규칙 문장("첫소리 ㄲ은 ㄱ 점자 앞에
   * 된소리표를 붙여 나타낸다")은 learning_skills에 있고, 이 메서드가 저장을 위해
   * 이미 그 엔티티를 조회한다 — 학습자가 "왜 틀렸는지"를 아는 경로를 여기서 잇는다.
   * 추가 조회는 없다.
   */
  private List<ViolatedSkill> saveSkillResults(
      ProblemAttempt attempt, AttemptAnalysisResult analysis) {
    Set<Long> skillIds =
        analysis.skillResults().stream()
            .map(LearningSessionAnalysisService.SkillResult::learningSkillId)
            .collect(Collectors.toSet());
    Map<Long, LearningSkill> skillsById =
        learningSkillRepository.findAllById(skillIds).stream()
            .collect(Collectors.toMap(LearningSkill::getId, Function.identity()));
    if (skillsById.size() != skillIds.size()) {
      throw new BusinessException(ErrorCode.LEARNING_ANALYSIS_UNAVAILABLE);
    }

    List<AttemptSkillResult> skillResults =
        analysis.skillResults().stream()
            .map(
                result ->
                    new AttemptSkillResult(
                        attempt,
                        skillsById.get(result.learningSkillId()),
                        result.correct(),
                        totalErrorCount(result.errorCounts())))
            .toList();
    List<AttemptSkillResult> savedSkillResults =
        attemptSkillResultRepository.saveAllAndFlush(skillResults);
    Map<Long, AttemptSkillResult> savedResultsBySkillId =
        savedSkillResults.stream()
            .collect(Collectors.toMap(result -> result.getSkill().getId(), Function.identity()));

    List<AttemptSkillResultError> errors = new ArrayList<>();
    for (LearningSessionAnalysisService.SkillResult result : analysis.skillResults()) {
      AttemptSkillResult savedResult = savedResultsBySkillId.get(result.learningSkillId());
      for (Map.Entry<String, Long> error : result.errorCounts().entrySet()) {
        ErrorType errorType = LearningSessionAnalysisValidator.parseErrorType(error.getKey());
        errors.add(
            new AttemptSkillResultError(savedResult, errorType, Math.toIntExact(error.getValue())));
      }
    }
    if (!errors.isEmpty()) {
      attemptSkillResultErrorRepository.saveAll(errors);
    }

    // AI가 준 순서를 지킨다 — 앞에 오는 것이 이번 오답에 가장 가까운 요소다
    return analysis.skillResults().stream()
        .filter(result -> !result.correct())
        .map(result -> skillsById.get(result.learningSkillId()))
        .map(skill -> new ViolatedSkill(skill.getId(), skill.getName(), skill.getDescription()))
        .toList();
  }

  private static int totalErrorCount(Map<String, Long> errorCounts) {
    long total = 0;
    for (Long count : errorCounts.values()) {
      total = Math.addExact(total, count);
    }
    return Math.toIntExact(total);
  }

  private static void validateCommand(AttemptCommand command) {
    if (command == null
        || command.problemSessionId() == null
        || command.problemSessionId() <= 0
        || command.inputPatterns() == null
        || command.inputPatterns().stream()
            .anyMatch(pattern -> pattern == null || pattern < 0 || pattern > MAX_PATTERN)) {
      throw new BusinessException(ErrorCode.INVALID_ATTEMPT_ANALYSIS_REQUEST);
    }
  }

  private static void validateExpectedCells(List<ProblemCell> expectedCells) {
    if (expectedCells.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_ATTEMPT_ANALYSIS_REQUEST);
    }
    for (int index = 0; index < expectedCells.size(); index++) {
      if (expectedCells.get(index).getCellIndex() != index + 1) {
        throw new IllegalStateException("문제의 정답 셀 순서가 연속적이지 않습니다.");
      }
    }
  }

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

  private static List<AttemptCell> createAttemptCells(
      ProblemAttempt attempt,
      List<ProblemCell> expectedCells,
      List<Integer> inputPatterns,
      LocalDateTime respondedAt) {
    int comparisonCellCount = Math.max(expectedCells.size(), inputPatterns.size());
    List<AttemptCell> cells = new ArrayList<>(comparisonCellCount);
    for (int index = 0; index < comparisonCellCount; index++) {
      int expectedPattern =
          index < expectedCells.size() ? expectedCells.get(index).getExpectedPattern() : 0;
      int inputPattern = index < inputPatterns.size() ? inputPatterns.get(index) : 0;
      cells.add(new AttemptCell(attempt, index + 1, expectedPattern, inputPattern, respondedAt));
    }
    return cells;
  }

  private static Long curriculumStageId(LearningSession session) {
    if (session.getCurriculumStage() == null) {
      return null;
    }
    Long stageId = session.getCurriculumStage().getId();
    if (stageId == null) {
      throw new IllegalStateException("학습 세션의 커리큘럼 단계 ID가 없습니다.");
    }
    return stageId;
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

  public record AttemptCommand(Long problemSessionId, List<Integer> inputPatterns) {
    public AttemptCommand {
      inputPatterns = inputPatterns == null ? null : List.copyOf(inputPatterns);
    }
  }

  public record AttemptResult(
      Long problemSessionId,
      Long problemId,
      Long attemptId,
      boolean correct,
      Instant attemptedAt,
      List<CellResult> cells,
      FeedbackResult feedback,
      List<ViolatedSkill> violatedSkills) {
    public AttemptResult {
      cells = List.copyOf(cells);
      violatedSkills = List.copyOf(violatedSkills);
    }
  }

  /** 이번 풀이에서 어긴 학습요소. `description`은 없을 수 있다(learning_skills.description은 TEXT NULL) */
  public record ViolatedSkill(Long id, String name, String description) {}

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
