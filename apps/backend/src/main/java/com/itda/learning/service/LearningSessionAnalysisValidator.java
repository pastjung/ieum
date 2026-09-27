package com.itda.learning.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.learning.dto.LearningAttemptAnalysisFastApiResponse;
import com.itda.learning.dto.LearningSessionSummaryFastApiResponse;
import com.itda.learning.dto.LearningSkillStatePayload;
import com.itda.problem.domain.ErrorType;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class LearningSessionAnalysisValidator {

  private LearningSessionAnalysisValidator() {}

  static void validateAttemptResponse(
      LearningAttemptAnalysisFastApiResponse response, Set<Long> relevantSkillIds) {
    if (response == null
        || response.feedback() == null
        || isBlank(response.feedback().tts())
        || (response.feedback().detail() != null && response.feedback().detail().isBlank())
        || response.skillResults() == null
        || response.updatedSkillStates() == null) {
      throw analysisUnavailable();
    }

    Set<Long> resultSkillIds = new HashSet<>();
    for (LearningAttemptAnalysisFastApiResponse.SkillResult result : response.skillResults()) {
      if (result == null
          || result.learningSkillId() == null
          || result.learningSkillId() <= 0
          || !resultSkillIds.add(result.learningSkillId())
          || result.errorCounts() == null) {
        throw analysisUnavailable();
      }
      long totalErrorCount = validateErrorCounts(result.errorCounts(), false);
      if (totalErrorCount > Integer.MAX_VALUE
          || result.errorCounts().values().stream().anyMatch(count -> count > Integer.MAX_VALUE)
          || result.correct() != (totalErrorCount == 0)) {
        throw analysisUnavailable();
      }
    }

    Set<Long> updatedSkillIds = validateUpdatedSkillStates(response.updatedSkillStates());
    if (!resultSkillIds.containsAll(relevantSkillIds)
        || !updatedSkillIds.containsAll(relevantSkillIds)
        || !updatedSkillIds.containsAll(resultSkillIds)) {
      throw analysisUnavailable();
    }
  }

  static void validateSummaryResponse(
      LearningSessionSummaryFastApiResponse response, Set<Long> relevantSkillIds) {
    if (response == null
        || isBlank(response.summary())
        || isBlank(response.ttsText())
        || response.errorTypeCounts() == null
        || response.weakSkills() == null) {
      throw analysisUnavailable();
    }
    validateErrorCounts(response.errorTypeCounts(), true);

    Set<Long> weakSkillIds = new HashSet<>();
    for (LearningSessionSummaryFastApiResponse.WeakSkill weakSkill : response.weakSkills()) {
      if (weakSkill == null
          || weakSkill.learningSkillId() == null
          || weakSkill.learningSkillId() <= 0
          || !weakSkillIds.add(weakSkill.learningSkillId())
          || !isProbability(weakSkill.masteryProb())
          || isBlank(weakSkill.label())
          || isBlank(weakSkill.explanation())) {
        throw analysisUnavailable();
      }
      if (weakSkill.dominantErrorType() != null) {
        parseErrorType(weakSkill.dominantErrorType());
      }
    }
    if (!relevantSkillIds.containsAll(weakSkillIds)) {
      throw analysisUnavailable();
    }
  }

  private static Set<Long> validateUpdatedSkillStates(
      Iterable<LearningSkillStatePayload> updatedSkillStates) {
    Set<Long> skillIds = new HashSet<>();
    for (LearningSkillStatePayload state : updatedSkillStates) {
      if (state == null
          || state.learningSkillId() == null
          || state.learningSkillId() <= 0
          || !skillIds.add(state.learningSkillId())
          || state.nAttempts() < 0
          || state.nCorrect() < 0
          || state.nCorrect() > state.nAttempts()
          || !isProbability(state.masteryProb())
          || state.errorCounts() == null
          || !isRecentResults(state.recentResults())) {
        throw analysisUnavailable();
      }
      validateErrorCounts(state.errorCounts(), true);
    }
    return skillIds;
  }

  private static long validateErrorCounts(Map<String, Long> errorCounts, boolean allowZero) {
    long total = 0;
    for (Map.Entry<String, Long> errorCount : errorCounts.entrySet()) {
      parseErrorType(errorCount.getKey());
      Long count = errorCount.getValue();
      if (count == null || count < 0 || (!allowZero && count == 0)) {
        throw analysisUnavailable();
      }
      try {
        total = Math.addExact(total, count);
      } catch (ArithmeticException exception) {
        throw analysisUnavailable();
      }
    }
    return total;
  }

  /** recentResults: 없거나(빈 문자열) '0'/'1' 로만 된 5자 이하 */
  private static boolean isRecentResults(String value) {
    return value == null
        || (value.length() <= 5 && value.chars().allMatch(c -> c == '0' || c == '1'));
  }

  static ErrorType parseErrorType(String value) {
    if (value == null || value.isBlank()) {
      throw analysisUnavailable();
    }
    try {
      ErrorType errorType = ErrorType.valueOf(value.toUpperCase(Locale.ROOT));
      if (!toErrorTypeValue(errorType).equals(value)) {
        throw analysisUnavailable();
      }
      return errorType;
    } catch (IllegalArgumentException exception) {
      throw analysisUnavailable();
    }
  }

  static String toErrorTypeValue(ErrorType errorType) {
    return errorType.name().toLowerCase(Locale.ROOT);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static boolean isProbability(Double value) {
    return value != null && Double.isFinite(value) && value >= 0 && value <= 1;
  }

  static BusinessException analysisUnavailable() {
    return new BusinessException(ErrorCode.LEARNING_ANALYSIS_UNAVAILABLE);
  }
}
