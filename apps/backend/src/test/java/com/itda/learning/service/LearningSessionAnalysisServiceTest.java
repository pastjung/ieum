package com.itda.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.itda.global.FastApiClient;
import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.learning.domain.UserSkillMemory;
import com.itda.learning.dto.LearningAttemptAnalysisFastApiRequest;
import com.itda.learning.dto.LearningAttemptAnalysisFastApiResponse;
import com.itda.learning.dto.LearningSessionSummaryFastApiRequest;
import com.itda.learning.dto.LearningSessionSummaryFastApiResponse;
import com.itda.learning.dto.LearningSkillStatePayload;
import com.itda.learning.dto.LearningSkillStatesFastApiResponse;
import com.itda.learning.service.LearningSessionAnalysisService.AnalysisAttempt;
import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemSkill;
import com.itda.problem.domain.SkillType;
import com.itda.problem.repository.LearningSkillRepository;
import com.itda.problem.repository.ProblemSkillRepository;
import com.itda.problem.repository.UserSkillErrorCountRepository;
import com.itda.problem.repository.UserSkillMemoryRepository;
import com.itda.user.repository.UserJpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LearningSessionAnalysisServiceTest {

  private static final long USER_ID = 1L;
  private static final long PROBLEM_ID = 20L;
  private static final long SKILL_ID = 23L;
  private static final LocalDateTime ATTEMPTED_AT = LocalDateTime.of(2026, 9, 25, 9, 41);

  @Mock private ProblemSkillRepository problemSkillRepository;
  @Mock private UserSkillMemoryRepository userSkillMemoryRepository;
  @Mock private UserSkillErrorCountRepository userSkillErrorCountRepository;
  @Mock private LearningSkillRepository learningSkillRepository;
  @Mock private UserJpaRepository userJpaRepository;
  @Mock private FastApiClient fastApiClient;

  private LearningSessionAnalysisService service;
  private LearningSkill skill;

  @BeforeEach
  void setUp() {
    service =
        new LearningSessionAnalysisService(
            problemSkillRepository,
            userSkillMemoryRepository,
            userSkillErrorCountRepository,
            learningSkillRepository,
            userJpaRepository,
            fastApiClient);

    skill = LearningSkill.of(SkillType.SKILL_03, "ㅇ", "받침 이응", null);
    ReflectionTestUtils.setField(skill, "id", SKILL_ID);
    Problem problem = org.mockito.Mockito.mock(Problem.class);
    given(problemSkillRepository.findAllWithSkillByProblemIdIn(List.of(PROBLEM_ID)))
        .willReturn(List.of(new ProblemSkill(problem, skill)));
    given(userSkillMemoryRepository.findAllByUser_Id(USER_ID)).willReturn(List.of());
    given(learningSkillRepository.findAllById(org.mockito.ArgumentMatchers.anySet()))
        .willReturn(List.of(skill));
  }

  private LearningSkillStatePayload skillStatePayload() {
    return new LearningSkillStatePayload(SKILL_ID, 1, 0, ATTEMPTED_AT, 0.3, Map.of("dot_missing", 1L));
  }

  @Test
  void 완성형_한글만_AI로_채점할_수_있다() {
    // apps/ai 의 kbraille.decompose 가 완성형 음절(U+AC00~U+D7A3)만 받는다
    assertThat(LearningSessionAnalysisService.isAiGradable("가방")).isTrue();
    assertThat(LearningSessionAnalysisService.isAiGradable("그래서")).isTrue();
    // 자음·모음 단계 문항 — 호환 자모라 AI가 422로 거절한다
    assertThat(LearningSessionAnalysisService.isAiGradable("ㄱ")).isFalse();
    assertThat(LearningSessionAnalysisService.isAiGradable("ㅙ")).isFalse();
    // 어절 하나만 받는다
    assertThat(LearningSessionAnalysisService.isAiGradable("가 방")).isFalse();
    assertThat(LearningSessionAnalysisService.isAiGradable("abc")).isFalse();
    assertThat(LearningSessionAnalysisService.isAiGradable("")).isFalse();
    assertThat(LearningSessionAnalysisService.isAiGradable(null)).isFalse();
  }

  @Test
  void 낱자_문항은_FastAPI_없이_문항의_학습요소로_채점한다() {
    LearningSessionAnalysisService.AttemptAnalysisResult result =
        service.analyzeAttemptLocally(
            USER_ID,
            new LearningSessionAnalysisService.AnalysisAttempt(
                PROBLEM_ID, "ㄱ", List.of(0b000101), List.of(0b000001)),
            ATTEMPTED_AT);

    // 점역 경로를 타지 않는다는 것은 아래 별도 테스트가 지킨다. 여기서는 채점 결과만 본다 —
    // 숙련도 갱신(POST /skill-states)은 FastAPI를 부르므로 verifyNoInteractions 는 더 이상 맞지 않다
    assertThat(result.skillResults())
        .singleElement()
        .satisfies(
            skillResult -> {
              assertThat(skillResult.learningSkillId()).isEqualTo(SKILL_ID);
              assertThat(skillResult.correct()).isFalse();
              // 정답 1·3번인데 1번만 눌러 3번이 빠졌다
              assertThat(skillResult.errorCounts()).isEqualTo(Map.of("dot_missing", 1L));
            });
  }

  @Test
  void 낱자_문항을_맞히면_오류_없이_정답으로_기록한다() {
    LearningSessionAnalysisService.AttemptAnalysisResult result =
        service.analyzeAttemptLocally(
            USER_ID,
            new LearningSessionAnalysisService.AnalysisAttempt(
                PROBLEM_ID, "ㄱ", List.of(0b000101), List.of(0b000101)),
            ATTEMPTED_AT);

    assertThat(result.skillResults())
        .singleElement()
        .satisfies(
            skillResult -> {
              assertThat(skillResult.correct()).isTrue();
              assertThat(skillResult.errorCounts()).isEmpty();
            });
  }

  @Test
  void 낱자_문항도_숙련도를_갱신한다() {
    // 예전에는 갱신하지 않았다. 숙련도는 AI의 BKT 추정기가 만드는 값이고 낱자는 점역기가
    // 분해하지 못해 그 경로를 탈 수 없었기 때문이다. 그 결과 자음·모음은 아무리 틀려도
    // 약한 요소로 뽑히지 않아 복습에 영영 나오지 않았다.
    //
    // 이제 글자 없이 숙련도만 갱신하는 경로(POST /skill-states)가 있다
    given(fastApiClient.updateSkillStates(org.mockito.ArgumentMatchers.any()))
        .willReturn(new LearningSkillStatesFastApiResponse(List.of(skillStatePayload())));
    given(userSkillMemoryRepository.saveAllAndFlush(org.mockito.ArgumentMatchers.anyList()))
        .willAnswer(
            invocation -> {
              List<UserSkillMemory> memories = invocation.getArgument(0);
              ReflectionTestUtils.setField(memories.getFirst(), "id", 100L);
              return memories;
            });
    given(userSkillErrorCountRepository.findAllByUserSkillMemory_IdIn(List.of(100L)))
        .willReturn(List.of());

    service.analyzeAttemptLocally(
        USER_ID,
        new LearningSessionAnalysisService.AnalysisAttempt(
            PROBLEM_ID, "ㄱ", List.of(0b000101), List.of(0b000001)),
        ATTEMPTED_AT);

    verify(userSkillMemoryRepository).saveAllAndFlush(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void 낱자_문항은_점역_경로를_타지_않는다() {
    // 글자를 보내면 같은 벽(non-Hangul 422)에 다시 부딪힌다
    given(fastApiClient.updateSkillStates(org.mockito.ArgumentMatchers.any()))
        .willReturn(new LearningSkillStatesFastApiResponse(List.of()));

    service.analyzeAttemptLocally(
        USER_ID,
        new LearningSessionAnalysisService.AnalysisAttempt(
            PROBLEM_ID, "ㄱ", List.of(0b000101), List.of(0b000001)),
        ATTEMPTED_AT);

    verify(fastApiClient, never()).analyzeLearningAttempt(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void 숙련도_갱신이_실패해도_채점_결과는_돌려준다() {
    // 숙련도는 복습 추천에 쓰이는 부가 정보다. 그것 때문에 문항 제출이 통째로 실패하면
    // 학습자는 답을 냈는데도 다음 문항으로 갈 수 없다
    given(fastApiClient.updateSkillStates(org.mockito.ArgumentMatchers.any()))
        .willThrow(new org.springframework.web.client.ResourceAccessException("연결 실패"));

    LearningSessionAnalysisService.AttemptAnalysisResult result =
        service.analyzeAttemptLocally(
            USER_ID,
            new LearningSessionAnalysisService.AnalysisAttempt(
                PROBLEM_ID, "ㄱ", List.of(0b000101), List.of(0b000001)),
            ATTEMPTED_AT);

    assertThat(result.skillResults()).isNotEmpty();
  }

  @Test
  void 단일_문제는_원본_입력과_현재_숙련도를_보내고_갱신본을_저장한다() {
    given(fastApiClient.analyzeLearningAttempt(org.mockito.ArgumentMatchers.any()))
        .willReturn(attemptResponse());
    given(userSkillMemoryRepository.saveAllAndFlush(org.mockito.ArgumentMatchers.anyList()))
        .willAnswer(
            invocation -> {
              List<UserSkillMemory> memories = invocation.getArgument(0);
              ReflectionTestUtils.setField(memories.getFirst(), "id", 100L);
              return memories;
            });
    given(userSkillErrorCountRepository.findAllByUserSkillMemory_IdIn(List.of(100L)))
        .willReturn(List.of());

    LearningSessionAnalysisService.AttemptAnalysisResult result =
        service.analyzeAttempt(USER_ID, attempt(), ATTEMPTED_AT);

    ArgumentCaptor<LearningAttemptAnalysisFastApiRequest> requestCaptor =
        ArgumentCaptor.forClass(LearningAttemptAnalysisFastApiRequest.class);
    verify(fastApiClient).analyzeLearningAttempt(requestCaptor.capture());
    assertThat(requestCaptor.getValue().attempt().targetText()).isEqualTo("가방");
    assertThat(requestCaptor.getValue().attempt().expectedPatterns()).containsExactly(43, 24, 54);
    assertThat(requestCaptor.getValue().attempt().inputPatterns()).containsExactly(43, 24, 32);
    assertThat(requestCaptor.getValue().skillStates())
        .singleElement()
        .satisfies(
            state -> {
              assertThat(state.learningSkillId()).isEqualTo(SKILL_ID);
              assertThat(state.nAttempts()).isZero();
              assertThat(state.masteryProb()).isNull();
            });
    assertThat(result.feedback().tts()).isEqualTo("세 번째 칸을 확인해 보세요.");

    ArgumentCaptor<List<UserSkillMemory>> memoryCaptor = ArgumentCaptor.forClass(List.class);
    verify(userSkillMemoryRepository).saveAllAndFlush(memoryCaptor.capture());
    assertThat(memoryCaptor.getValue())
        .singleElement()
        .satisfies(
            memory -> {
              assertThat(memory.getTotalSeen()).isEqualTo(1);
              assertThat(memory.getTotalCorrect()).isZero();
              assertThat(memory.getLastPracticedAt()).isEqualTo(ATTEMPTED_AT);
              assertThat(memory.getMasteryProbability()).isEqualTo(0.22);
            });
  }

  @Test
  void 전체_요약은_모든_문제와_최신_숙련도를_보내고_DB를_갱신하지_않는다() {
    UserSkillMemory memory =
        new UserSkillMemory(
            org.mockito.Mockito.mock(com.itda.user.domain.User.class),
            skill,
            13,
            4,
            ATTEMPTED_AT,
            0.22);
    ReflectionTestUtils.setField(memory, "id", 100L);
    given(userSkillMemoryRepository.findAllByUser_Id(USER_ID)).willReturn(List.of(memory));
    given(userSkillErrorCountRepository.findAllByUserSkillMemory_IdIn(List.of(100L)))
        .willReturn(List.of());
    given(fastApiClient.summarizeLearningSession(org.mockito.ArgumentMatchers.any()))
        .willReturn(summaryResponse());

    LearningSessionAnalysisService.SessionSummaryResult result =
        service.summarizeSession(USER_ID, List.of(attempt()));

    ArgumentCaptor<LearningSessionSummaryFastApiRequest> requestCaptor =
        ArgumentCaptor.forClass(LearningSessionSummaryFastApiRequest.class);
    verify(fastApiClient).summarizeLearningSession(requestCaptor.capture());
    assertThat(requestCaptor.getValue().attempts()).hasSize(1);
    assertThat(requestCaptor.getValue().skillStates())
        .singleElement()
        .satisfies(
            state -> {
              assertThat(state.nAttempts()).isEqualTo(13);
              assertThat(state.nCorrect()).isEqualTo(4);
              assertThat(state.lastPracticedAt()).isEqualTo(ATTEMPTED_AT);
            });
    assertThat(result.errorTypeCounts()).containsEntry("dot_missing", 1L);
    verify(userSkillMemoryRepository, never())
        .saveAllAndFlush(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void FastAPI_호출_실패는_분석_불가_오류로_변환한다() {
    given(fastApiClient.analyzeLearningAttempt(org.mockito.ArgumentMatchers.any()))
        .willThrow(new RestClientException("connection failed"));

    assertThatThrownBy(() -> service.analyzeAttempt(USER_ID, attempt(), ATTEMPTED_AT))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.LEARNING_ANALYSIS_UNAVAILABLE));
  }

  private static AnalysisAttempt attempt() {
    return new AnalysisAttempt(PROBLEM_ID, "가방", List.of(43, 24, 54), List.of(43, 24, 32));
  }

  private static LearningAttemptAnalysisFastApiResponse attemptResponse() {
    return new LearningAttemptAnalysisFastApiResponse(
        new LearningAttemptAnalysisFastApiResponse.Feedback(
            "세 번째 칸을 확인해 보세요.", "받침 이응은 2, 3, 5, 6점입니다."),
        List.of(
            new LearningAttemptAnalysisFastApiResponse.SkillResult(
                SKILL_ID, false, Map.of("dot_missing", 1L))),
        List.of(
            new LearningSkillStatePayload(
                SKILL_ID, 1, 0, ATTEMPTED_AT, 0.22, Map.of("dot_missing", 1L))));
  }

  private static LearningSessionSummaryFastApiResponse summaryResponse() {
    return new LearningSessionSummaryFastApiResponse(
        "받침 이응을 연습해 보세요.",
        "받침 이응을 다시 연습해 보세요.",
        Map.of("dot_missing", 1L),
        List.of(
            new LearningSessionSummaryFastApiResponse.WeakSkill(
                SKILL_ID, 0.22, "더 연습해요", "dot_missing", "점 누락이 반복됐어요.")));
  }
}
