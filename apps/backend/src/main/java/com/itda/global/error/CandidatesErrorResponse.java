package com.itda.global.error;

import com.itda.global.error.CandidatesBusinessException.Candidate;
import java.util.List;

/** 공통 오류 body {code, message}에 candidates만 더한 형태 (API 명세 8.2.1절). */
public record CandidatesErrorResponse(String code, String message, List<Candidate> candidates) {

  static CandidatesErrorResponse of(CandidatesBusinessException e) {
    ErrorCode errorCode = e.getErrorCode();
    return new CandidatesErrorResponse(
        errorCode.getCode(), errorCode.getMessage(), e.getCandidates());
  }
}
