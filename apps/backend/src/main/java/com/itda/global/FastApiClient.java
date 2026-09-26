package com.itda.global;

import com.itda.learning.dto.LearningAttemptAnalysisFastApiRequest;
import com.itda.learning.dto.LearningAttemptAnalysisFastApiResponse;
import com.itda.learning.dto.LearningSessionSummaryFastApiRequest;
import com.itda.learning.dto.LearningSessionSummaryFastApiResponse;
import com.itda.learning.dto.LearningSkillStatesFastApiRequest;
import com.itda.learning.dto.LearningSkillStatesFastApiResponse;
import com.itda.learning.dto.LearningSkillSummaryFastApiRequest;
import com.itda.learning.dto.LearningSkillSummaryFastApiResponse;
import com.itda.problem.dto.ProblemAnalyzeResponse;
import com.itda.problem.dto.RegisterProblemRequest;
import com.itda.problem.dto.ReviewAnalyzeRequest;
import com.itda.problem.dto.ReviewAnalyzeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .retrieve()
        .body(ProblemAnalyzeResponse.class);
  }

  public ReviewAnalyzeResponse reviewAnalyze(ReviewAnalyzeRequest request) {
    return restClient
        .post()
        .uri("/reviewAnalyze")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .retrieve()
        .body(ReviewAnalyzeResponse.class);
  }

  public LearningAttemptAnalysisFastApiResponse analyzeLearningAttempt(
      LearningAttemptAnalysisFastApiRequest request) {
    return restClient
        .post()
        .uri("/attempt-analyze")
        .body(request)
        .retrieve()
        .body(LearningAttemptAnalysisFastApiResponse.class);
  }

  /** 세션 밖에서 요소별 숙련 상태를 읽는다. 상태를 바꾸지 않는다 */
  public LearningSkillSummaryFastApiResponse summarizeSkills(
      LearningSkillSummaryFastApiRequest request) {
    return restClient
        .post()
        .uri("/skill-summary")
        .body(request)
        .retrieve()
        .body(LearningSkillSummaryFastApiResponse.class);
  }

  /** 글자 없이 숙련도만 갱신한다. 낱자 문항(자음·모음)이 쓰는 경로다 */
  public LearningSkillStatesFastApiResponse updateSkillStates(
      LearningSkillStatesFastApiRequest request) {
    return restClient
        .post()
        .uri("/skill-states")
        .body(request)
        .retrieve()
        .body(LearningSkillStatesFastApiResponse.class);
  }

  public LearningSessionSummaryFastApiResponse summarizeLearningSession(
      LearningSessionSummaryFastApiRequest request) {
    return restClient
        .post()
        .uri("/session-summary")
        .body(request)
        .retrieve()
        .body(LearningSessionSummaryFastApiResponse.class);
  }
}
