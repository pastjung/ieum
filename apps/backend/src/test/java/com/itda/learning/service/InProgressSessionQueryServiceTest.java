package com.itda.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.itda.curriculum.domain.CurriculumStage;
import com.itda.learning.domain.LearningSession;
import com.itda.learning.domain.LearningSessionRepository;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemSession;
import com.itda.problem.repository.ProblemSessionRepository;
import com.itda.user.domain.User;
import com.itda.user.repository.UserJpaRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 진행 중인 학습 세션 조회.
 *
 * <p>홈 화면의 "이어서 학습"이 오랫동안 목 데이터("3단계 자음과 모음 · 7 / 10 문항")를 보여주고, 누르면 진행 중이던 세션이 아니라 새 자음 세션을
 * 만들었다. 앱이 이어받을 세션의 식별자를 알 방법이 없었기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InProgressSessionQueryServiceTest {

  private static final long USER_ID = 1L;

  @Mock private LearningSessionRepository learningSessionRepository;
  @Mock private ProblemSessionRepository problemSessionRepository;
  @Mock private UserJpaRepository userJpaRepository;

  private InProgressSessionQueryService service;
  private User user;

  @BeforeEach
  void setUp() {
    service =
        new InProgressSessionQueryService(
            learningSessionRepository, problemSessionRepository, userJpaRepository);
    user = org.mockito.Mockito.mock(User.class);
    given(userJpaRepository.findById(USER_ID)).willReturn(Optional.of(user));
  }

  private LearningSession session(long id, CurriculumStage stage) {
    LearningSession learningSession = new LearningSession(user, stage);
    ReflectionTestUtils.setField(learningSession, "id", id);
    return learningSession;
  }

  private ProblemSession assigned(LearningSession session, long sequence, boolean completed) {
    ProblemSession problemSession =
        new ProblemSession(org.mockito.Mockito.mock(Problem.class), session, sequence);
    if (completed) {
      problemSession.complete();
    }
    return problemSession;
  }

  @Test
  void 진행_중인_단계_학습을_진도와_함께_돌려준다() {
    CurriculumStage stage = org.mockito.Mockito.mock(CurriculumStage.class);
    given(stage.getName()).willReturn("단어");
    LearningSession learningSession = session(7L, stage);
    given(learningSessionRepository.findAllByUserAndCurriculumStageIsNotNullAndCompletedAtIsNull(user))
        .willReturn(List.of(learningSession));
    given(learningSessionRepository.findAllByUserAndCurriculumStageIsNullAndCompletedAtIsNull(user))
        .willReturn(List.of());
    given(problemSessionRepository.findAllBySession_IdOrderBySequence(7L))
        .willReturn(
            List.of(
                assigned(learningSession, 1L, true),
                assigned(learningSession, 2L, true),
                assigned(learningSession, 3L, false)));

    InProgressSessionQueryService.InProgressSession result =
        service.find(USER_ID).orElseThrow();

    assertThat(result.sessionId()).isEqualTo(7L);
    assertThat(result.stageName()).isEqualTo("단어");
    // 종류가 필요한 이유: 새 세션을 만들면 같은 종류의 미완료 세션만 닫힌다
    // (ProblemService.createSession). 앱이 "이 학습을 시작하면 진행 중인 것이 닫힌다"를
    // 판단하려면 표시용 이름이 아니라 종류를 봐야 한다
    assertThat(result.review()).isFalse();
    assertThat(result.totalCount()).isEqualTo(3);
    assertThat(result.completedCount()).isEqualTo(2);
  }

  @Test
  void 복습_세션은_단계가_없으므로_복습이라고_부른다() {
    // 복습은 createSession(userId, null)로 만들어 curriculum_stage가 비어 있다
    LearningSession learningSession = session(9L, null);
    given(learningSessionRepository.findAllByUserAndCurriculumStageIsNotNullAndCompletedAtIsNull(user))
        .willReturn(List.of());
    given(learningSessionRepository.findAllByUserAndCurriculumStageIsNullAndCompletedAtIsNull(user))
        .willReturn(List.of(learningSession));
    given(problemSessionRepository.findAllBySession_IdOrderBySequence(9L))
        .willReturn(List.of(assigned(learningSession, 1L, false)));

    InProgressSessionQueryService.InProgressSession result =
        service.find(USER_ID).orElseThrow();

    assertThat(result.sessionId()).isEqualTo(9L);
    assertThat(result.stageName()).isEqualTo("복습");
    assertThat(result.review()).isTrue();
  }

  @Test
  void 진행_중인_세션이_없으면_비어_있다() {
    given(learningSessionRepository.findAllByUserAndCurriculumStageIsNotNullAndCompletedAtIsNull(user))
        .willReturn(List.of());
    given(learningSessionRepository.findAllByUserAndCurriculumStageIsNullAndCompletedAtIsNull(user))
        .willReturn(List.of());

    assertThat(service.find(USER_ID)).isEmpty();
  }

  @Test
  void 배정된_문항이_없는_세션은_이어할_것이_없으므로_내보내지_않는다() {
    // 문항 배정 전에 실패한 세션이 남을 수 있다. "0 / 0 문항"을 이어서 학습으로 보여주면
    // 눌러도 풀 것이 없다
    CurriculumStage stage = org.mockito.Mockito.mock(CurriculumStage.class);
    LearningSession learningSession = session(11L, stage);
    given(learningSessionRepository.findAllByUserAndCurriculumStageIsNotNullAndCompletedAtIsNull(user))
        .willReturn(List.of(learningSession));
    given(learningSessionRepository.findAllByUserAndCurriculumStageIsNullAndCompletedAtIsNull(user))
        .willReturn(List.of());
    given(problemSessionRepository.findAllBySession_IdOrderBySequence(11L)).willReturn(List.of());

    assertThat(service.find(USER_ID)).isEmpty();
  }

  @Test
  void 모두_푼_세션도_내보낸다_완료_제출이_남았다() {
    // 마지막 문항까지 풀고 완료 제출만 실패한 경우다. 빼 버리면 그 세션이 목록에서 사라져
    // 학습자가 푼 12문항이 영영 결과를 보지 못하고 갇힌다 — 실제로 그렇게 됐다
    CurriculumStage stage = org.mockito.Mockito.mock(CurriculumStage.class);
    LearningSession learningSession = session(13L, stage);
    given(learningSessionRepository.findAllByUserAndCurriculumStageIsNotNullAndCompletedAtIsNull(user))
        .willReturn(List.of(learningSession));
    given(learningSessionRepository.findAllByUserAndCurriculumStageIsNullAndCompletedAtIsNull(user))
        .willReturn(List.of());
    given(problemSessionRepository.findAllBySession_IdOrderBySequence(13L))
        .willReturn(List.of(assigned(learningSession, 1L, true)));

    InProgressSessionQueryService.InProgressSession result = service.find(USER_ID).orElseThrow();

    assertThat(result.totalCount()).isEqualTo(1);
    assertThat(result.completedCount()).isEqualTo(1);
  }
}
