package com.itda.lecture.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.dto.ChangeLectureStatusRequest;
import com.itda.lecture.dto.CreateLectureRequest;
import com.itda.lecture.dto.InstructorLectureResponse;
import com.itda.lecture.dto.LearnerLectureResponse;
import com.itda.lecture.dto.LectureListResponse;
import com.itda.lecture.dto.MediaTokenResponse;
import com.itda.lecture.service.LectureMediaService;
import com.itda.lecture.service.LectureService;
import com.itda.lecture.service.LectureSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API 명세 8.2·8.5절. 경로·body에서 사용자 식별자를 받지 않는다 — 대상은 principal뿐이다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/lectures")
public class LectureController {

  private final LectureService lectureService;
  private final LectureMediaService lectureMediaService;

  @PostMapping
  public ResponseEntity<InstructorLectureResponse> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateLectureRequest request) {
    LectureSnapshot created =
        lectureService.create(
            user, request.title(), request.description(), request.scheduledAtUtc());
    return ResponseEntity.status(HttpStatus.CREATED).body(InstructorLectureResponse.from(created));
  }

  @GetMapping
  public LectureListResponse list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) LectureStatus status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return LectureListResponse.from(lectureService.listMine(user, status, page, size));
  }

  /** 강사와 학습자의 응답 필드가 달라 본문 타입이 요청자에 따라 갈린다. */
  @GetMapping("/{lectureId}")
  public Object find(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Positive Long lectureId) {
    LectureSnapshot snapshot = lectureService.find(user, lectureId);
    return snapshot.isInstructorView()
        ? InstructorLectureResponse.from(snapshot)
        : LearnerLectureResponse.from(snapshot);
  }

  @PutMapping("/{lectureId}/status")
  public InstructorLectureResponse changeStatus(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable @Positive Long lectureId,
      @Valid @RequestBody ChangeLectureStatusRequest request) {
    return InstructorLectureResponse.from(
        lectureService.changeStatus(user, lectureId, request.status()));
  }

  @PostMapping("/{lectureId}/media-tokens")
  public ResponseEntity<MediaTokenResponse> issueMediaToken(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Positive Long lectureId) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(MediaTokenResponse.from(lectureMediaService.issue(user, lectureId)));
  }
}
