package com.itda.learning.service;

import com.itda.global.FastApiClient;
import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.learning.domain.UserSkillMemory;
import com.itda.learning.dto.LearningAnalysisAttemptPayload;
import com.itda.learning.dto.LearningAttemptAnalysisFastApiRequest;
import com.itda.learning.dto.LearningAttemptAnalysisFastApiResponse;
import com.itda.learning.dto.LearningSessionSummaryFastApiRequest;
import com.itda.learning.dto.LearningSessionSummaryFastApiResponse;
import com.itda.learning.dto.LearningSkillStatePayload;
import com.itda.problem.domain.ErrorType;
import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.ProblemSkill;
import com.itda.problem.domain.UserSkillErrorCount;
import com.itda.problem.repository.LearningSkillRepository;
import com.itda.problem.repository.ProblemSkillRepository;
import com.itda.problem.repository.UserSkillErrorCountRepository;
import com.itda.problem.repository.UserSkillMemoryRepository;
import com.itda.user.domain.User;
import com.itda.user.repository.UserJpaRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

@Service
@RequiredArgsConstructor
@Slf4j
public class LearningSessionAnalysisService {

  private final ProblemSkillRepository problemSkillRepository;
  private final UserSkillMemoryRepository userSkillMemoryRepository;
  private final UserSkillErrorCountRepository userSkillErrorCountRepository;
  private final LearningSkillRepository learningSkillRepository;
  private final UserJpaRepository userJpaRepository;
  private final FastApiClient fastApiClient;

  @Transactional(propagation = Propagation.MANDATORY)
  public AttemptAnalysisResult analyzeAttempt(
      long userId, AnalysisAttempt attempt, LocalDateTime attemptedAt) {
    SkillStateContext context = loadSkillStateContext(userId, List.of(attempt.problemId()));
    LearningAttemptAnalysisFastApiRequest request =
        new LearningAttemptAnalysisFastApiRequest(toAttemptPayload(attempt), context.skillStates());
    LearningAttemptAnalysisFastApiResponse response = requestAttemptAnalysis(request);

    LearningSessionAnalysisValidator.validateAttemptResponse(response, context.relevantSkillIds());
    Map<Long, LearningSkill> referencedSkills = loadLearningSkills(referencedSkillIds(response));
    persistUpdatedSkillStates(userId, response.updatedSkillStates(), referencedSkills, attemptedAt);
    return toAttemptAnalysisResult(response);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public SessionSummaryResult summarizeSession(long userId, List<AnalysisAttempt> attempts) {
    List<Long> problemIds = attempts.stream().map(AnalysisAttempt::problemId).toList();
    SkillStateContext context = loadSkillStateContext(userId, problemIds);
    LearningSessionSummaryFastApiRequest request =
        new LearningSessionSummaryFastApiRequest(
            attempts.stream().map(LearningSessionAnalysisService::toAttemptPayload).toList(),
            context.skillStates());
    LearningSessionSummaryFastApiResponse response = requestSessionSummary(request);

    LearningSessionAnalysisValidator.validateSummaryResponse(response, context.relevantSkillIds());
    loadLearningSkills(
        response.weakSkills().stream()
            .map(LearningSessionSummaryFastApiResponse.WeakSkill::learningSkillId)
            .collect(Collectors.toSet()));
    return toSessionSummaryResult(response);
  }

  private SkillStateContext loadSkillStateContext(long userId, List<Long> problemIds) {
    Map<Long, LearningSkill> relevantSkills =
        problemSkillRepository.findAllWithSkillByProblemIdIn(problemIds).stream()
            .map(ProblemSkill::getSkill)
            .collect(
                Collectors.toMap(
                    LearningSkill::getId,
                    Function.identity(),
                    (first, second) -> first,
                    TreeMap::new));
    Map<Long, UserSkillMemory> memoriesBySkillId =
        userSkillMemoryRepository.findAllByUser_Id(userId).stream()
            .collect(
                Collectors.toMap(
                    memory -> memory.getSkill().getId(),
                    Function.identity(),
                    (first, second) -> first));
    Map<Long, Map<String, Long>> errorCountsByMemoryId =
        loadRelevantErrorCounts(relevantSkills.keySet(), memoriesBySkillId);
    List<LearningSkillStatePayload> skillStates =
        relevantSkills.keySet().stream()
            .map(
                skillId ->
                    toSkillState(skillId, memoriesBySkillId.get(skillId), errorCountsByMemoryId))
            .toList();
    return new SkillStateContext(skillStates, Set.copyOf(relevantSkills.keySet()));
  }

  private Map<Long, Map<String, Long>> loadRelevantErrorCounts(
      Set<Long> relevantSkillIds, Map<Long, UserSkillMemory> memoriesBySkillId) {
    List<Long> memoryIds =
        relevantSkillIds.stream()
            .map(memoriesBySkillId::get)
            .filter(memory -> memory != null && memory.getId() != null)
            .map(UserSkillMemory::getId)
            .toList();
    if (memoryIds.isEmpty()) {
      return Map.of();
    }

    Map<Long, Map<String, Long>> result = new HashMap<>();
    for (UserSkillErrorCount errorCount :
        userSkillErrorCountRepository.findAllByUserSkillMemory_IdIn(memoryIds)) {
      Long replaced =
          result
              .computeIfAbsent(errorCount.getUserSkillMemory().getId(), ignored -> new TreeMap<>())
              .put(
                  LearningSessionAnalysisValidator.toErrorTypeValue(errorCount.getErrorType()),
                  errorCount.getErrorCount());
      if (replaced != null) {
        throw new IllegalStateException("학습 요소 오류 횟수가 중복 저장되어 있습니다.");
      }
    }
    return result;
  }

  private static LearningAnalysisAttemptPayload toAttemptPayload(AnalysisAttempt attempt) {
    return new LearningAnalysisAttemptPayload(
        attempt.targetText(), attempt.expectedPatterns(), attempt.inputPatterns());
  }

  private static LearningSkillStatePayload toSkillState(
      Long skillId, UserSkillMemory memory, Map<Long, Map<String, Long>> errorCountsByMemoryId) {
    if (memory == null) {
      return new LearningSkillStatePayload(skillId, 0, 0, null, null, Map.of());
    }
    return new LearningSkillStatePayload(
        skillId,
        memory.getTotalSeen(),
        memory.getTotalCorrect(),
        memory.getLastPracticedAt(),
        memory.getMasteryProbability(),
        errorCountsByMemoryId.getOrDefault(memory.getId(), Map.of()));
  }

  private LearningAttemptAnalysisFastApiResponse requestAttemptAnalysis(
      LearningAttemptAnalysisFastApiRequest request) {
    try {
      return fastApiClient.analyzeLearningAttempt(request);
    } catch (RestClientException exception) {
      log.warn("FastAPI attempt analysis request failed", exception);
      throw new BusinessException(ErrorCode.LEARNING_ANALYSIS_UNAVAILABLE);
    }
  }

  private LearningSessionSummaryFastApiResponse requestSessionSummary(
      LearningSessionSummaryFastApiRequest request) {
    try {
      return fastApiClient.summarizeLearningSession(request);
    } catch (RestClientException exception) {
      log.warn("FastAPI session summary request failed", exception);
      throw new BusinessException(ErrorCode.LEARNING_ANALYSIS_UNAVAILABLE);
    }
  }

  private Map<Long, LearningSkill> loadLearningSkills(Set<Long> skillIds) {
    if (skillIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, LearningSkill> skillsById =
        learningSkillRepository.findAllById(skillIds).stream()
            .collect(Collectors.toMap(LearningSkill::getId, Function.identity()));
    if (skillsById.size() != skillIds.size()) {
      throw LearningSessionAnalysisValidator.analysisUnavailable();
    }
    return skillsById;
  }

  private static Set<Long> referencedSkillIds(LearningAttemptAnalysisFastApiResponse response) {
    Set<Long> skillIds =
        response.updatedSkillStates().stream()
            .map(LearningSkillStatePayload::learningSkillId)
            .collect(Collectors.toCollection(HashSet::new));
    response.skillResults().stream()
        .map(LearningAttemptAnalysisFastApiResponse.SkillResult::learningSkillId)
        .forEach(skillIds::add);
    return skillIds;
  }

  private void persistUpdatedSkillStates(
      long userId,
      List<LearningSkillStatePayload> updatedStates,
      Map<Long, LearningSkill> skillsById,
      LocalDateTime attemptedAt) {
    if (updatedStates.isEmpty()) {
      return;
    }

    Set<Long> skillIds =
        updatedStates.stream()
            .map(LearningSkillStatePayload::learningSkillId)
            .collect(Collectors.toSet());
    Map<Long, UserSkillMemory> memoriesBySkillId =
        userSkillMemoryRepository.findAllByUser_Id(userId).stream()
            .filter(memory -> skillIds.contains(memory.getSkill().getId()))
            .collect(Collectors.toMap(memory -> memory.getSkill().getId(), Function.identity()));

    User userReference = userJpaRepository.getReferenceById(userId);
    List<UserSkillMemory> updatedMemories = new ArrayList<>();
    for (LearningSkillStatePayload state : updatedStates) {
      UserSkillMemory memory = memoriesBySkillId.get(state.learningSkillId());
      if (memory == null) {
        memory =
            new UserSkillMemory(
                userReference,
                skillsById.get(state.learningSkillId()),
                state.nAttempts(),
                state.nCorrect(),
                attemptedAt,
                state.masteryProb());
        memoriesBySkillId.put(state.learningSkillId(), memory);
      } else {
        memory.applyAnalysisSnapshot(
            state.nAttempts(), state.nCorrect(), attemptedAt, state.masteryProb());
      }
      updatedMemories.add(memory);
    }

    userSkillMemoryRepository.saveAllAndFlush(updatedMemories);
    synchronizeErrorCounts(updatedStates, memoriesBySkillId);
  }

  private void synchronizeErrorCounts(
      List<LearningSkillStatePayload> updatedStates, Map<Long, UserSkillMemory> memoriesBySkillId) {
    List<Long> memoryIds =
        updatedStates.stream()
            .map(state -> memoriesBySkillId.get(state.learningSkillId()).getId())
            .toList();
    Map<Long, Map<ErrorType, UserSkillErrorCount>> existingByMemoryId =
        loadStoredErrorCounts(memoryIds);

    List<UserSkillErrorCount> toSave = new ArrayList<>();
    List<UserSkillErrorCount> toDelete = new ArrayList<>();
    for (LearningSkillStatePayload state : updatedStates) {
      UserSkillMemory memory = memoriesBySkillId.get(state.learningSkillId());
      Map<ErrorType, Long> desiredCounts = toErrorCountMap(state.errorCounts());
      Map<ErrorType, UserSkillErrorCount> existingCounts =
          existingByMemoryId.getOrDefault(memory.getId(), Map.of());

      for (Map.Entry<ErrorType, UserSkillErrorCount> existing : existingCounts.entrySet()) {
        Long desiredCount = desiredCounts.get(existing.getKey());
        if (desiredCount == null || desiredCount == 0) {
          toDelete.add(existing.getValue());
        } else {
          existing.getValue().updateCount(desiredCount);
          toSave.add(existing.getValue());
        }
      }
      for (Map.Entry<ErrorType, Long> desired : desiredCounts.entrySet()) {
        if (desired.getValue() > 0 && !existingCounts.containsKey(desired.getKey())) {
          toSave.add(new UserSkillErrorCount(memory, desired.getKey(), desired.getValue()));
        }
      }
    }

    if (!toDelete.isEmpty()) {
      userSkillErrorCountRepository.deleteAllInBatch(toDelete);
    }
    if (!toSave.isEmpty()) {
      userSkillErrorCountRepository.saveAll(toSave);
    }
  }

  private Map<Long, Map<ErrorType, UserSkillErrorCount>> loadStoredErrorCounts(
      List<Long> memoryIds) {
    Map<Long, Map<ErrorType, UserSkillErrorCount>> result = new HashMap<>();
    for (UserSkillErrorCount existing :
        userSkillErrorCountRepository.findAllByUserSkillMemory_IdIn(memoryIds)) {
      UserSkillErrorCount duplicate =
          result
              .computeIfAbsent(
                  existing.getUserSkillMemory().getId(), ignored -> new EnumMap<>(ErrorType.class))
              .put(existing.getErrorType(), existing);
      if (duplicate != null) {
        throw new IllegalStateException("학습 요소 오류 횟수가 중복 저장되어 있습니다.");
      }
    }
    return result;
  }

  private static Map<ErrorType, Long> toErrorCountMap(Map<String, Long> errorCounts) {
    Map<ErrorType, Long> result = new EnumMap<>(ErrorType.class);
    for (Map.Entry<String, Long> entry : errorCounts.entrySet()) {
      ErrorType type = LearningSessionAnalysisValidator.parseErrorType(entry.getKey());
      if (result.put(type, entry.getValue()) != null) {
        throw LearningSessionAnalysisValidator.analysisUnavailable();
      }
    }
    return result;
  }

  private static AttemptAnalysisResult toAttemptAnalysisResult(
      LearningAttemptAnalysisFastApiResponse response) {
    List<SkillResult> skillResults =
        response.skillResults().stream()
            .map(
                result ->
                    new SkillResult(
                        result.learningSkillId(), result.correct(), result.errorCounts()))
            .toList();
    return new AttemptAnalysisResult(
        new FeedbackResult(response.feedback().tts(), response.feedback().detail()), skillResults);
  }

  private static SessionSummaryResult toSessionSummaryResult(
      LearningSessionSummaryFastApiResponse response) {
    List<WeakSkillResult> weakSkills =
        response.weakSkills().stream()
            .map(
                skill ->
                    new WeakSkillResult(
                        skill.learningSkillId(),
                        skill.masteryProb(),
                        skill.label(),
                        skill.dominantErrorType(),
                        skill.explanation()))
            .toList();
    return new SessionSummaryResult(
        response.summary(), response.ttsText(), response.errorTypeCounts(), weakSkills);
  }

  public record AnalysisAttempt(
      Long problemId,
      String targetText,
      List<Integer> expectedPatterns,
      List<Integer> inputPatterns) {
    public AnalysisAttempt {
      expectedPatterns = List.copyOf(expectedPatterns);
      inputPatterns = List.copyOf(inputPatterns);
    }
  }

  public record AttemptAnalysisResult(FeedbackResult feedback, List<SkillResult> skillResults) {
    public AttemptAnalysisResult {
      skillResults = List.copyOf(skillResults);
    }
  }

  public record FeedbackResult(String tts, String detail) {}

  public record SkillResult(Long learningSkillId, boolean correct, Map<String, Long> errorCounts) {
    public SkillResult {
      errorCounts = Map.copyOf(errorCounts);
    }
  }

  public record SessionSummaryResult(
      String summary,
      String ttsText,
      Map<String, Long> errorTypeCounts,
      List<WeakSkillResult> weakSkills) {
    public SessionSummaryResult {
      errorTypeCounts = Map.copyOf(errorTypeCounts);
      weakSkills = List.copyOf(weakSkills);
    }

    public static SessionSummaryResult notAvailable() {
      return new SessionSummaryResult(null, null, Map.of(), List.of());
    }
  }

  public record WeakSkillResult(
      Long learningSkillId,
      Double masteryProb,
      String label,
      String dominantErrorType,
      String explanation) {}

  private record SkillStateContext(
      List<LearningSkillStatePayload> skillStates, Set<Long> relevantSkillIds) {}
}
