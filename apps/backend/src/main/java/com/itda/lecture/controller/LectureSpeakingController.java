package com.itda.lecture.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.dto.ChangeSpeakingRequest;
import com.itda.lecture.dto.SpeakingResponse;
import com.itda.lecture.service.LectureSpeakingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 담당 강사가 참여 학습자의 발언을 허용·회수한다 (API 명세 8.5.1절). 대상 학습자를 경로로 받으므로 principal만 대상으로 삼는 {@link
 * LectureController}와 나눈다.
 */
@RestController
@RequiredArgsConstructor
public class LectureSpeakingController {

  private final LectureSpeakingService speakingService;

  /** 멱등 — 같은 값을 다시 보내도 결과가 같다. */
  @PutMapping("/lectures/{lectureId}/participants/{userId}/speaking")
  public SpeakingResponse changeSpeaking(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable @Positive Long lectureId,
      @PathVariable @Positive Long userId,
      @Valid @RequestBody ChangeSpeakingRequest request) {
    speakingService.changeSpeaking(user, lectureId, userId, request.allowed());
    return SpeakingResponse.of(userId, request.allowed());
  }
}
