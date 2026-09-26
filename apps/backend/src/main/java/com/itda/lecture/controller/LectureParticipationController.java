package com.itda.lecture.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.dto.JoinLectureRequest;
import com.itda.lecture.dto.LearnerLectureResponse;
import com.itda.lecture.service.LectureParticipationService;
import com.itda.lecture.service.LectureParticipationService.ParticipationResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class LectureParticipationController {

  private final LectureParticipationService participationService;

  /** 최초 입장 201, 재입장 200 (API 명세 8.2절). */
  @PostMapping("/lecture-participations")
  public ResponseEntity<LearnerLectureResponse> join(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody JoinLectureRequest request) {
    ParticipationResult result = participationService.join(user, request.participationCode());
    return ResponseEntity.status(result.firstJoin() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(LearnerLectureResponse.from(result.snapshot()));
  }
}
