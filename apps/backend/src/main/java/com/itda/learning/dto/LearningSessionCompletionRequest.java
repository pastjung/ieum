package com.itda.learning.dto;

import com.itda.learning.service.LearningSessionCompletionService.AttemptCommand;
import com.itda.learning.service.LearningSessionCompletionService.CompletionCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record LearningSessionCompletionRequest(
    @NotEmpty @Size(max = 100) List<@Valid AttemptRequest> attempts) {

  public CompletionCommand toCommand() {
    return new CompletionCommand(attempts.stream().map(AttemptRequest::toCommand).toList());
  }

  public record AttemptRequest(
      @NotNull @Positive Long problemSessionId,
      @NotEmpty @Size(max = 100) List<@NotNull @Min(0) @Max(63) Integer> inputPatterns) {

    AttemptCommand toCommand() {
      return new AttemptCommand(problemSessionId, inputPatterns);
    }
  }
}
