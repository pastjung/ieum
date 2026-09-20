package com.itda.global;

import com.itda.problem.dto.ProblemAnalyzeResponse;
import com.itda.problem.dto.RegisterProblemRequest;
import com.itda.problem.dto.ReviewAnalyzeRequest;
import com.itda.problem.dto.ReviewAnalyzeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class FastApiClient {

  private final RestClient restClient;

  public ProblemAnalyzeResponse analyze(RegisterProblemRequest request) {
    return restClient
        .post()
        .uri("/analyze")
        .body(request)
        .retrieve()
        .body(ProblemAnalyzeResponse.class);
  }

  public ReviewAnalyzeResponse reviewAnalyze(ReviewAnalyzeRequest request) {
    return restClient
        .post()
        .uri("/reviewAnalyze")
        .body(request)
        .retrieve()
        .body(ReviewAnalyzeResponse.class);
  }
}
