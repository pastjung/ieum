package com.itda.problem.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.problem.dto.LinkedProblemResponse;
import com.itda.problem.dto.NewStudyRequest;
import com.itda.problem.dto.ProblemsResponse;
import com.itda.problem.dto.RegisterProblemRequest;
import com.itda.problem.service.ProblemService;
import com.itda.problem.service.RegisteredProblem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ProblemController {
  private final ProblemService problemService;

  /** 201 새 연결 / 200 이미 연결됨 (API 명세 8.2.1절). */
  @PostMapping("/lectures/{lectureId}/problems")
  public ResponseEntity<LinkedProblemResponse> registerProblem(
      @PathVariable @Positive Long lectureId,
      @Valid @RequestBody RegisterProblemRequest registerProblemRequest,
      @AuthenticationPrincipal AuthenticatedUser user) {
    RegisteredProblem registered;
    try {
      registered = problemService.registerProblem(user, lectureId, registerProblemRequest);
    } catch (DataIntegrityViolationException e) {
      // 같은 문제를 동시에 연결하면 한쪽이 (lecture_id, problem_id) UNIQUE에 걸린다. 그 트랜잭션은 이미 롤백됐으므로
      // 다시 불러 기존 연결(200)로 답한다.
      registered = problemService.registerProblem(user, lectureId, registerProblemRequest);
    }
    return ResponseEntity.status(registered.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(LinkedProblemResponse.from(registered.problem()));
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
