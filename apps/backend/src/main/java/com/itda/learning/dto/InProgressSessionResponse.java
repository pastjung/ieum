package com.itda.learning.dto;

import com.itda.learning.service.InProgressSessionQueryService.InProgressSession;

/**
 * 진행 중인 학습 세션. 홈 화면의 "이어서 학습"이 제목·진도를 보여주고 이 {@code sessionId}로 세션을 이어받는다.
 *
 * <p>진행 중인 것이 없으면 이 본문 대신 {@code 204}다 — 빈 객체를 보내면 "없음"과 "0문항짜리 세션"을 구별할 수 없다.
 */
public record InProgressSessionResponse(
    Long sessionId, String stageName, int totalCount, int completedCount) {

  public static InProgressSessionResponse from(InProgressSession session) {
    return new InProgressSessionResponse(
        session.sessionId(), session.stageName(), session.totalCount(), session.completedCount());
  }
}
