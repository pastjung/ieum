package com.itda.learning.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.learning.dto.LearningAttemptAnalysisRequest;
import com.itda.learning.dto.LearningAttemptAnalysisResponse;
import com.itda.learning.dto.LearningSessionCompletionRequest;
import com.itda.learning.dto.LearningSessionCompletionResponse;
import com.itda.learning.service.LearningAttemptService;
import com.itda.learning.service.LearningSessionCompletionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/learning-sessions")
public class LearningSessionCompletionController {

  private final LearningSessionCompletionService completionService;
  private final LearningAttemptService attemptService;

  @PostMapping("/{sessionId}/attempt-analysis")
  public ResponseEntity<LearningAttemptAnalysisResponse> analyzeAttempt(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable @Positive Long sessionId,
      @Valid @RequestBody LearningAttemptAnalysisRequest request) {
    LearningAttemptService.AttemptResult result =
        attemptService.analyze(user.userId(), sessionId, request.toCommand());
    return ResponseEntity.ok(LearningAttemptAnalysisResponse.from(result));
  }

  @PostMapping("/{sessionId}/completion")
  public ResponseEntity<LearningSessionCompletionResponse> complete(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable @Positive Long sessionId,
      @Valid @RequestBody LearningSessionCompletionRequest request) {
    LearningSessionCompletionService.CompletionResult result =
        completionService.complete(user.userId(), sessionId, request.toCommand());
    return ResponseEntity.ok(LearningSessionCompletionResponse.from(result));
  }
}
