package com.itda.learning.dto;

import com.itda.learning.service.LearningAttemptService.AttemptCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record LearningAttemptAnalysisRequest(
    @NotNull @Positive Long problemSessionId,
    @NotNull List<@NotNull @Min(0) @Max(63) Integer> inputPatterns) {

  public AttemptCommand toCommand() {
    return new AttemptCommand(problemSessionId, inputPatterns);
  }
}
