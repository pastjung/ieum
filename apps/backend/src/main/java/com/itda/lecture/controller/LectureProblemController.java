package com.itda.lecture.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.dto.DeliverProblemRequest;
import com.itda.lecture.dto.InstructorLectureResponse;
import com.itda.lecture.dto.LectureAnswersResponse;
import com.itda.lecture.dto.LiveAnswerResponse;
import com.itda.lecture.dto.SubmitAnswerRequest;
import com.itda.lecture.service.LectureAnswerService;
import com.itda.lecture.service.LectureDeliveryService;
import com.itda.lecture.service.SubmittedAnswer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수업의 현재 문제와 LIVE 답안 (API 명세 8.2·8.2.2절). 문제 연결(POST /problems)은 문제 모듈의 {@code ProblemController}가
 * 맡는다.
 *
 * <p>클래스에 {@code @Validated}를 붙이지 않는다 — 파라미터 제약 검증은 Spring MVC 기본 메서드 검증이 400으로 처리한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/lectures/{lectureId}")
public class LectureProblemController {

  /** UUID v4. 형식만 검사하고 저장하지 않는다 (API 명세 8.2.2절). */
  private static final String UUID_V4 =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";

  private final LectureDeliveryService deliveryService;
  private final LectureAnswerService answerService;

  /** 같은 문제 재요청도 200 — metadata만 다시 쓴다. */
  @PutMapping("/current-problem")
  public InstructorLectureResponse deliver(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable @Positive Long lectureId,
      @Valid @RequestBody DeliverProblemRequest request) {
    return InstructorLectureResponse.from(
        deliveryService.deliver(user, lectureId, request.problemIdValue()));
  }

  /** 201 새 풀이 / 200 같은 cells 재전송. 헤더 누락·형식 오류와 durationMs 오류는 400 (API 명세 8.4절). */
  @PostMapping("/problems/{problemId}/answers")
  public ResponseEntity<LiveAnswerResponse> submitAnswer(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable @Positive Long lectureId,
      @PathVariable @Positive Long problemId,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = UUID_V4) String idempotencyKey,
      @Valid @RequestBody SubmitAnswerRequest request) {
    SubmittedAnswer submitted = answerService.submit(user, lectureId, problemId, request.cells());
    return ResponseEntity.status(submitted.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(LiveAnswerResponse.from(submitted.answer()));
  }

  @GetMapping("/problems/{problemId}/answers")
  public LectureAnswersResponse listAnswers(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable @Positive Long lectureId,
      @PathVariable @Positive Long problemId) {
    return LectureAnswersResponse.from(answerService.listAnswers(user, lectureId, problemId));
  }
}
