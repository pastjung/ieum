package com.itda.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.global.error.CandidatesBusinessException.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** PROBLEM_TEXT_AMBIGUOUS는 공통 오류 body에 candidates만 더한다 (API 명세 8.2.1절). */
class CandidatesErrorResponseTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  private static CandidatesBusinessException ambiguous() {
    return new CandidatesBusinessException(
        ErrorCode.PROBLEM_TEXT_AMBIGUOUS,
        List.of(new Candidate("초성 ㄱ", "초성 ㄱ"), new Candidate("받침 ㄱ", "받침 ㄱ")));
  }

  @Test
  void 후보_목록을_공통_오류_body에_더한다() {
    ResponseEntity<CandidatesErrorResponse> response =
        handler.handleCandidates(ambiguous(), new MockHttpServletRequest());

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    CandidatesErrorResponse body = response.getBody();
    assertThat(body.code()).isEqualTo("PROBLEM_TEXT_AMBIGUOUS");
    assertThat(body.message()).isEqualTo(ErrorCode.PROBLEM_TEXT_AMBIGUOUS.getMessage());
    assertThat(body.candidates())
        .containsExactly(new Candidate("초성 ㄱ", "초성 ㄱ"), new Candidate("받침 ㄱ", "받침 ㄱ"));
  }

  @Test
  void JSON을_받지_않는_요청이면_상태는_두고_body만_생략한다() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Accept", "text/html");

    ResponseEntity<CandidatesErrorResponse> response =
        handler.handleCandidates(ambiguous(), request);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody()).isNull();
  }
}
