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
import com.itda.learning.dto.LearningSkillStatesFastApiRequest;
import com.itda.learning.dto.LearningSkillStatesFastApiResponse;
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

  /** 완성형 한글 음절의 코드 범위 (U+AC00~U+D7A3) */
  private static final int HANGUL_SYLLABLE_FIRST = 0xAC00;

  private static final int HANGUL_SYLLABLE_COUNT = 11172;

  /**
   * FastAPI 채점기가 이 문항을 다룰 수 있는지.
   *
   * <p><b>2026-09-26 이후 호출 경로에서 쓰지 않는다.</b> apps/ai 가 낱자('ㄱ'·'ㅙ')와 드릴 텍스트를 직접 받게 되어(drill_items.py,
   * expectedPatterns 로 첫소리·받침 판정) 우회가 필요 없어졌다. AI 장애 시 폴백 후보로만 남겨 둔다.
   *
   * <p>AI는 {@code target_text}를 자모로 분해해 규칙을 적용한다 ({@code apps/ai} 의 {@code kbraille.decompose}). 그
   * 함수는 완성형 한글 음절만 받고, 낱자('ㄱ'·'ㅙ')를 만나면 {@code non-Hangul}로 거절한다 — 백엔드에는 422로 돌아오고 학습자는 "학습 결과를 분석할
   * 수 없습니다"를 본다.
   *
   * <p>자음·모음 단계 문항이 전부 낱자다(자음 28개, 모음 21개). 즉 커리큘럼 1·2단계는 AI를 거치는 한 한 문제도 풀 수 없다. 그 문항들은 규칙 변환이 필요
   * 없으므로 — 정답 점형이 이미 {@code problem_cells}에 있고 어긴 규칙은 {@code problem_skills}가 가리킨다 — AI를 부르지 않고
   * {@link #analyzeAttemptLocally}로 채점한다.
   */
  public static boolean isAiGradable(String targetText) {
    if (targetText == null || targetText.isBlank()) {
      return false;
    }
    return targetText
        .codePoints()
        .allMatch(
            codePoint -> {
              int offset = codePoint - HANGUL_SYLLABLE_FIRST;
              return offset >= 0 && offset < HANGUL_SYLLABLE_COUNT;
            });
  }

  /**
   * AI를 거치지 않고 채점한다. 낱자 문항 전용이다.
   *
   * <p>정오는 정답 셀과의 비교로, 어긴 학습요소는 {@code problem_skills}가 가리키는 요소로 정한다. AI가 이 문항에서 더할 수 있는 것은 규칙 설명
   * 문장 하나인데, 그것은 이미 {@code learning_skills.description}에 있고 응답의 {@code violatedSkills}로 나간다.
   *
   * <p>숙련도(user_skill_memory)도 갱신한다. 그 값은 AI의 BKT 추정기가 만들지만, 점역을 거치지 않고 요소별 정오만으로 갱신하는 경로({@code
   * POST /skill-states})가 있다. 이것이 없던 동안에는 자음·모음이 아무리 틀려도 약한 요소로 뽑히지 않아 복습에 영영 나오지 않았다.
   *
   * <p>그 갱신이 실패해도 채점 결과는 돌려준다. 숙련도는 복습 추천에 쓰이는 부가 정보이고, 그것 때문에 문항 제출이 통째로 실패하면 학습자는 답을 냈는데도 다음 문항으로
   * 갈 수 없다.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AttemptAnalysisResult analyzeAttemptLocally(
      long userId, AnalysisAttempt attempt, LocalDateTime attemptedAt) {
    Map<String, Long> errorCounts =
        AttemptErrorClassifier.classify(attempt.expectedPatterns(), attempt.inputPatterns());
    boolean correct = errorCounts.isEmpty();
    List<SkillResult> skillResults =
        problemSkillRepository.findAllWithSkillByProblemIdIn(List.of(attempt.problemId())).stream()
            .map(ProblemSkill::getSkill)
            .map(LearningSkill::getId)
            .distinct()
            .sorted()
            .map(skillId -> new SkillResult(skillId, correct, errorCounts))
            .toList();
    updateSkillStatesQuietly(userId, attempt, skillResults, attemptedAt);
    return new AttemptAnalysisResult(new FeedbackResult("", ""), skillResults);
  }

  /** 숙련도 갱신. 실패해도 풀이 자체는 살린다 (위 javadoc 참고) */
  private void updateSkillStatesQuietly(
      long userId,
      AnalysisAttempt attempt,
      List<SkillResult> skillResults,
      LocalDateTime attemptedAt) {
    if (skillResults.isEmpty()) {
      return;
    }
    try {
      SkillStateContext context = loadSkillStateContext(userId, List.of(attempt.problemId()));
      // RestClient 는 빈 응답 본문에 null 을 돌려준다 — 그대로 쓰면 풀이 저장까지 NPE 로 무너진다
      LearningSkillStatesFastApiResponse response =
          fastApiClient.updateSkillStates(
              new LearningSkillStatesFastApiRequest(
                  context.skillStates(),
                  skillResults.stream()
                      .map(
                          result ->
                              new LearningSkillStatesFastApiRequest.SkillOutcome(
                                  result.learningSkillId(), result.correct(), result.errorCounts()))
                      .toList()));
      if (response == null || response.updatedSkillStates().isEmpty()) {
        return;
      }
      Map<Long, LearningSkill> referencedSkills =
          loadLearningSkills(
              response.updatedSkillStates().stream()
                  .map(LearningSkillStatePayload::learningSkillId)
                  .collect(Collectors.toSet()));
      persistUpdatedSkillStates(
          userId, response.updatedSkillStates(), referencedSkills, attemptedAt);
    } catch (RestClientException exception) {
      log.warn("FastAPI skill state update failed", exception);
    }
  }

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

    /**
     * AI를 거치지 않은 세션의 요약. 낱자 문항으로만 이루어진 세션에서 쓴다.
     *
     * <p>조언 문장과 약한 요소는 AI만 만들 수 있어 비운다. 오류 유형 집계는 직접 세어 채운다 — 비워 두면 결과 화면이 "눈에 띄는 실수가 없었어요"라고 잘못
     * 말한다.
     */
    public static SessionSummaryResult errorCountsOnly(Map<String, Long> errorTypeCounts) {
      return new SessionSummaryResult(null, null, errorTypeCounts, List.of());
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
