package com.itda.learning.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.learning.dto.InProgressSessionResponse;
import com.itda.learning.dto.LearningAttemptAnalysisRequest;
import com.itda.learning.dto.LearningAttemptAnalysisResponse;
import com.itda.learning.dto.LearningSessionCompletionRequest;
import com.itda.learning.dto.LearningSessionCompletionResponse;
import com.itda.learning.service.InProgressSessionQueryService;
import com.itda.learning.service.LearningAttemptService;
import com.itda.learning.service.LearningSessionCompletionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/learning-sessions")
public class LearningSessionCompletionController {

  private final LearningSessionCompletionService completionService;
  private final LearningAttemptService attemptService;
  private final InProgressSessionQueryService inProgressSessionQueryService;

  /**
   * 진행 중인 학습 세션. 없으면 {@code 204}다.
   *
   * <p>대상은 인증 필터가 확인한 principal뿐이다 — path·query의 사용자 식별자를 받지 않는다.
   */
  @GetMapping("/in-progress")
  public ResponseEntity<InProgressSessionResponse> inProgress(
      @AuthenticationPrincipal AuthenticatedUser user) {
    return inProgressSessionQueryService
        .find(user.userId())
        .map(InProgressSessionResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

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
