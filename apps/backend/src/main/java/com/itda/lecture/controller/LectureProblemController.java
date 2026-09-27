package com.itda.lecture.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.dto.DeliverProblemRequest;
import com.itda.lecture.dto.InstructorLectureResponse;
import com.itda.lecture.service.LectureDeliveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

  private final LectureDeliveryService deliveryService;

  /** 같은 문제 재요청도 200 — metadata만 다시 쓴다. */
  @PutMapping("/current-problem")
  public InstructorLectureResponse deliver(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable @Positive Long lectureId,
      @Valid @RequestBody DeliverProblemRequest request) {
    return InstructorLectureResponse.from(
        deliveryService.deliver(user, lectureId, request.problemIdValue()));
  }
}
