package com.itda.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.itda.curriculum.domain.CurriculumStage;
import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.learning.domain.AttemptCellRepository;
import com.itda.learning.domain.AttemptSkillResult;
import com.itda.learning.domain.AttemptSkillResultError;
import com.itda.learning.domain.AttemptSkillResultErrorRepository;
import com.itda.learning.domain.AttemptSkillResultRepository;
import com.itda.learning.domain.LearningSession;
import com.itda.learning.domain.LearningSessionRepository;
import com.itda.learning.domain.ProblemAttempt;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.learning.service.LearningAttemptService.AttemptCommand;
import com.itda.learning.service.LearningSessionAnalysisService.AttemptAnalysisResult;
import com.itda.learning.service.LearningSessionAnalysisService.FeedbackResult;
import com.itda.learning.service.LearningSessionAnalysisService.SkillResult;
import com.itda.problem.domain.ErrorType;
import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.ProblemSession;
import com.itda.problem.domain.SkillType;
import com.itda.problem.repository.LearningSkillRepository;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemSessionRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LearningAttemptServiceTest {

  private static final long USER_ID = 1L;
  private static final long SESSION_ID = 10L;
  private static final long PROBLEM_ID = 20L;
  private static final long PROBLEM_SESSION_ID = 30L;
  private static final long SKILL_ID = 23L;

  @Mock private LearningSessionRepository learningSessionRepository;
  @Mock private ProblemSessionRepository problemSessionRepository;
  @Mock private ProblemCellRepository problemCellRepository;
  @Mock private ProblemAttemptRepository problemAttemptRepository;
  @Mock private AttemptCellRepository attemptCellRepository;
  @Mock private AttemptSkillResultRepository attemptSkillResultRepository;
  @Mock private AttemptSkillResultErrorRepository attemptSkillResultErrorRepository;
  @Mock private LearningSkillRepository learningSkillRepository;
  @Mock private LearningSessionAnalysisService analysisService;

  private LearningAttemptService service;
  private LearningSession session;
  private ProblemSession problemSession;

  @BeforeEach
  void setUp() {
    service =
        new LearningAttemptService(
            learningSessionRepository,
            problemSessionRepository,
            problemCellRepository,
            problemAttemptRepository,
            attemptCellRepository,
            attemptSkillResultRepository,
            attemptSkillResultErrorRepository,
            learningSkillRepository,
            analysisService);

    CurriculumStage stage = org.mockito.Mockito.mock(CurriculumStage.class);
    given(stage.getId()).willReturn(5L);
    com.itda.user.domain.User owner = org.mockito.Mockito.mock(com.itda.user.domain.User.class);
    given(owner.getId()).willReturn(USER_ID);
    session = new LearningSession(owner, stage);
    ReflectionTestUtils.setField(session, "id", SESSION_ID);
    Problem problem = new Problem(Level.WORD, "가방");
    ReflectionTestUtils.setField(problem, "id", PROBLEM_ID);
    problemSession = new ProblemSession(problem, session, 1L);
    ReflectionTestUtils.setField(problemSession, "id", PROBLEM_SESSION_ID);

    given(learningSessionRepository.findByIdForCompletion(SESSION_ID))
        .willReturn(Optional.of(session));
    given(problemSessionRepository.findAllForCompletion(SESSION_ID))
        .willReturn(List.of(problemSession));
    given(problemCellRepository.findByProblemIdOrderByCellIndexAsc(PROBLEM_ID))
        .willReturn(List.of(new ProblemCell(problem, 1, 43)));
    given(
            analysisService.analyzeAttempt(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .willReturn(
            new AttemptAnalysisResult(
                new FeedbackResult("첫 번째 칸을 확인해 보세요.", "정답 점형을 확인해 보세요."),
                List.of(new SkillResult(SKILL_ID, false, Map.of("dot_missing", 1L)))));
    given(problemAttemptRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
        .willAnswer(
            invocation -> {
              ProblemAttempt attempt = invocation.getArgument(0);
              ReflectionTestUtils.setField(attempt, "id", 100L);
              return attempt;
            });
    given(attemptCellRepository.saveAllAndFlush(org.mockito.ArgumentMatchers.anyList()))
        .willAnswer(invocation -> invocation.getArgument(0));

    LearningSkill skill =
        LearningSkill.of(
            SkillType.SKILL_03, "ㅇ", "받침 이응", "받침 ㅇ은 1, 2, 3, 5, 6점으로 나타낸다.");
    ReflectionTestUtils.setField(skill, "id", SKILL_ID);
    given(learningSkillRepository.findAllById(org.mockito.ArgumentMatchers.anySet()))
        .willReturn(List.of(skill));
    given(attemptSkillResultRepository.saveAllAndFlush(org.mockito.ArgumentMatchers.anyList()))
        .willAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void 단일_문제_분석_결과를_풀이와_요소별_오류로_저장한다() {
    LearningAttemptService.AttemptResult result =
        service.analyze(USER_ID, SESSION_ID, new AttemptCommand(PROBLEM_SESSION_ID, List.of(32)));

    assertThat(result.correct()).isFalse();
    assertThat(result.cells())
        .singleElement()
        .satisfies(
            cell -> {
              assertThat(cell.expectedPattern()).isEqualTo(43);
              assertThat(cell.inputPattern()).isEqualTo(32);
            });
    assertThat(problemSession.isCompleted()).isTrue();

    ArgumentCaptor<List<AttemptSkillResult>> resultCaptor = ArgumentCaptor.forClass(List.class);
    verify(attemptSkillResultRepository).saveAllAndFlush(resultCaptor.capture());
    assertThat(resultCaptor.getValue())
        .singleElement()
        .satisfies(
            skillResult -> {
              assertThat(skillResult.getSkill().getId()).isEqualTo(SKILL_ID);
              assertThat(skillResult.isCorrect()).isFalse();
              assertThat(skillResult.getErrorCount()).isEqualTo(1);
            });

    ArgumentCaptor<List<AttemptSkillResultError>> errorCaptor = ArgumentCaptor.forClass(List.class);
    verify(attemptSkillResultErrorRepository).saveAll(errorCaptor.capture());
    assertThat(errorCaptor.getValue())
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.getErrorType()).isEqualTo(ErrorType.DOT_MISSING);
              assertThat(error.getErrorCount()).isEqualTo(1);
            });
  }

  @Test
  void 어긴_학습요소의_규칙_문장을_응답에_싣는다() {
    // 학습자가 "왜 틀렸는지" 아는 유일한 경로다. AI는 어긴 요소의 id만 알려주고,
    // 규칙 문장은 learning_skills에 있다 — 이 둘을 잇는 자리가 여기다
    LearningAttemptService.AttemptResult result =
        service.analyze(USER_ID, SESSION_ID, new AttemptCommand(PROBLEM_SESSION_ID, List.of(32)));

    assertThat(result.violatedSkills())
        .singleElement()
        .satisfies(
            skill -> {
              assertThat(skill.id()).isEqualTo(SKILL_ID);
              assertThat(skill.name()).isEqualTo("받침 이응");
              assertThat(skill.description()).isEqualTo("받침 ㅇ은 1, 2, 3, 5, 6점으로 나타낸다.");
            });
  }

  @Test
  void 맞힌_학습요소는_어긴_목록에_넣지_않는다() {
    given(
            analysisService.analyzeAttempt(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .willReturn(
            new AttemptAnalysisResult(
                new FeedbackResult("확인해 보세요.", "정답 점형을 확인해 보세요."),
                List.of(new SkillResult(SKILL_ID, true, Map.of()))));

    LearningAttemptService.AttemptResult result =
        service.analyze(USER_ID, SESSION_ID, new AttemptCommand(PROBLEM_SESSION_ID, List.of(32)));

    assertThat(result.violatedSkills()).isEmpty();
  }

  @Test
  void 입력_셀이_비어_있어도_누락으로_저장한다() {
    LearningAttemptService.AttemptResult result =
        service.analyze(USER_ID, SESSION_ID, new AttemptCommand(PROBLEM_SESSION_ID, List.of()));

    assertThat(result.cells())
        .singleElement()
        .satisfies(
            cell -> {
              assertThat(cell.inputPattern()).isZero();
              assertThat(cell.missingMask()).isEqualTo(43);
            });
  }

  @Test
  void 분석이_실패하면_풀이를_저장하거나_문제를_완료하지_않는다() {
    given(
            analysisService.analyzeAttempt(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .willThrow(new BusinessException(ErrorCode.LEARNING_ANALYSIS_UNAVAILABLE));

    assertThatThrownBy(
            () ->
                service.analyze(
                    USER_ID, SESSION_ID, new AttemptCommand(PROBLEM_SESSION_ID, List.of(32))))
        .isInstanceOf(BusinessException.class);

    verify(problemAttemptRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    assertThat(problemSession.isCompleted()).isFalse();
  }
}
