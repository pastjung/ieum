package com.itda.problem.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.problem.dto.NewStudyRequest;
import com.itda.problem.dto.ProblemsResponse;
import com.itda.problem.dto.RegisterProblemRequest;
import com.itda.problem.service.ProblemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ProblemController {
  private final ProblemService problemService;

  @PostMapping("/lectures/{lectureId}/problems")
  public ResponseEntity<Void> registerProblem(
      @PathVariable Long lectureId,
      @RequestBody RegisterProblemRequest registerProblemRequest,
      @AuthenticationPrincipal AuthenticatedUser user) {
    problemService.registerProblem(user, lectureId, registerProblemRequest);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/learning-sessions")
  public ResponseEntity<ProblemsResponse> newLearning(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestBody NewStudyRequest newStudyRequest) {
    ProblemsResponse problemsResponse =
        problemService.learningWithNewSession(user, newStudyRequest);
    return ResponseEntity.ok(problemsResponse);
  }

  @GetMapping("/learning-sessions/{sessionId}")
  public ResponseEntity<ProblemsResponse> oldLearning(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long sessionId) {
    ProblemsResponse problemsResponse = problemService.studyWithOldSession(user, sessionId);
    return ResponseEntity.ok(problemsResponse);
  }

  @PostMapping("/review-sessions")
  public ResponseEntity<ProblemsResponse> newReview(
      @AuthenticationPrincipal AuthenticatedUser user) {
    ProblemsResponse problemsResponse = problemService.reviewWithNewSession(user);
    return ResponseEntity.ok(problemsResponse);
  }

  @GetMapping("/review-sessions/{sessionId}")
  public ResponseEntity<ProblemsResponse> oldReview(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long sessionId) {
    ProblemsResponse problemsResponse = problemService.studyWithOldSession(user, sessionId);
    return ResponseEntity.ok(problemsResponse);
  }
}
