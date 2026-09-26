package com.itda.global.error;

import java.util.List;
import lombok.Getter;

/** 오류 body에 고를 수 있는 후보를 함께 싣는 비즈니스 예외 (API 명세 8.2.1절 PROBLEM_TEXT_AMBIGUOUS). */
@Getter
public class CandidatesBusinessException extends BusinessException {

  private final List<Candidate> candidates;

  public CandidatesBusinessException(ErrorCode errorCode, List<Candidate> candidates) {
    super(errorCode);
    this.candidates = List.copyOf(candidates);
  }

  /**
   * @param input 클라이언트가 그대로 다시 보낼 요청 값
   * @param displayText 후보 버튼 문구
   */
  public record Candidate(String input, String displayText) {}
}
