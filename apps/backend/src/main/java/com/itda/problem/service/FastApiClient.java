package com.itda.problem.service;

import com.itda.problem.dto.ProblemAnalyzeResponse;
import com.itda.problem.dto.RegisterProblemRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FastApiClient {

    private final RestClient restClient;

    public ProblemAnalyzeResponse analyze(RegisterProblemRequest request) {

        return restClient.post()
            .uri("/analyze")
            .body(request)
            .retrieve()
            .body(ProblemAnalyzeResponse.class);
    }
}
