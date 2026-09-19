package com.itda.global;

import com.itda.problem.dto.ProblemAnalyzeResponse;
import com.itda.problem.dto.RegisterProblemRequest;
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
}
