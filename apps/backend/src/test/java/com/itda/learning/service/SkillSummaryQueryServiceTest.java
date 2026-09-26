package com.itda.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.itda.global.FastApiClient;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.learning.domain.UserSkillMemory;
import com.itda.learning.dto.LearningSkillSummaryFastApiResponse;
import com.itda.learning.dto.LearningSkillSummaryFastApiResponse.SkillMastery;
import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.SkillType;
import com.itda.problem.repository.LearningSkillRepository;
import com.itda.problem.repository.UserSkillMemoryRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

/**
 * "내 실력" 화면과 홈 복습 카드가 읽는 숙련 상태.
 *
 * <p>지금까지 숙련 라벨은 세션 완료 응답({@code weakSkills})에만 실려서, 두 화면은 고정값을 내 실력처럼 보여주고 있었다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillSummaryQueryServiceTest {

  private static final long USER_ID = 1L;

  @Mock private UserSkillMemoryRepository userSkillMemoryRepository;
  @Mock private LearningSkillRepository learningSkillRepository;
  @Mock private ProblemAttemptRepository problemAttemptRepository;
  @Mock private FastApiClient fastApiClient;

  private SkillSummaryQueryService service;

  @BeforeEach
  void setUp() {
    service =
        new SkillSummaryQueryService(
            userSkillMemoryRepository,
            learningSkillRepository,
            problemAttemptRepository,
            fastApiClient);
    given(userSkillMemoryRepository.findAllByUser_Id(USER_ID)).willReturn(List.of());
    given(problemAttemptRepository.countByUserId(USER_ID)).willReturn(184L);
    given(problemAttemptRepository.countByUserIdAndCorrectTrue(USER_ID)).willReturn(151L);
  }

  /** 숙련 기록이 있어야 AI에 물어본다 — 없으면 물어볼 것이 없어 조기 반환한다 */
  private void givenMemories(long... skillIds) {
    List<UserSkillMemory> memories = new ArrayList<>();
    for (long skillId : skillIds) {
      UserSkillMemory memory = mock(UserSkillMemory.class);
      LearningSkill skill = skill(skillId, "요소 " + skillId);
      given(memory.getSkill()).willReturn(skill);
      given(memory.getTotalSeen()).willReturn(4);
      given(memory.getTotalCorrect()).willReturn(3);
      given(memory.getMasteryProbability()).willReturn(0.5);
      memories.add(memory);
    }
    given(userSkillMemoryRepository.findAllByUser_Id(USER_ID)).willReturn(memories);
  }

  private LearningSkill skill(long id, String name) {
    LearningSkill learningSkill = LearningSkill.of(SkillType.SKILL_03, "ㅇ", name, "설명");
    ReflectionTestUtils.setField(learningSkill, "id", id);
    return learningSkill;
  }

  @Test
  void 요소_이름과_숙련_라벨을_이어_돌려준다() {
    // AI는 id·숙련도·라벨만 안다. 사람이 읽을 이름은 learning_skills 에 있다
    givenMemories(23L);
    given(fastApiClient.summarizeSkills(org.mockito.ArgumentMatchers.any()))
        .willReturn(
            new LearningSkillSummaryFastApiResponse(
                List.of(new SkillMastery(23L, 0.42, "더 연습해요"))));
    given(learningSkillRepository.findAllById(org.mockito.ArgumentMatchers.anySet()))
        .willReturn(List.of(skill(23L, "받침 기본 자음")));

    SkillSummaryQueryService.SkillSummary summary = service.find(USER_ID);

    assertThat(summary.skills())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.learningSkillId()).isEqualTo(23L);
              assertThat(s.name()).isEqualTo("받침 기본 자음");
              // 같은 name 이 여러 행에 걸쳐 있다 — "첫소리 기본 자음"만 14행이다.
              // 대상 글자가 없으면 화면에 같은 이름이 반복돼 학습자가 구별할 수 없다
              assertThat(s.target()).isEqualTo("ㅇ");
              assertThat(s.masteryProb()).isEqualTo(0.42);
              assertThat(s.label()).isEqualTo("더 연습해요");
            });
  }

  @Test
  void 풀이_횟수와_정답_횟수를_함께_돌려준다() {
    given(fastApiClient.summarizeSkills(org.mockito.ArgumentMatchers.any()))
        .willReturn(new LearningSkillSummaryFastApiResponse(List.of()));

    SkillSummaryQueryService.SkillSummary summary = service.find(USER_ID);

    assertThat(summary.totalAttemptCount()).isEqualTo(184);
    assertThat(summary.correctAttemptCount()).isEqualTo(151);
  }

  @Test
  void 이름을_찾지_못한_요소는_내보내지_않는다() {
    // 이름 없이 숙련도만 보여주면 학습자는 무엇이 약한지 알 수 없다
    givenMemories(23L);
    given(fastApiClient.summarizeSkills(org.mockito.ArgumentMatchers.any()))
        .willReturn(
            new LearningSkillSummaryFastApiResponse(
                List.of(new SkillMastery(23L, 0.42, "더 연습해요"))));
    given(learningSkillRepository.findAllById(org.mockito.ArgumentMatchers.anySet()))
        .willReturn(List.of());

    assertThat(service.find(USER_ID).skills()).isEmpty();
  }

  @Test
  void AI가_응답하지_않으면_통계만_돌려준다() {
    // 숙련 라벨이 없다고 화면 전체가 실패하면, 풀이 기록까지 볼 수 없게 된다
    givenMemories(23L);
    given(fastApiClient.summarizeSkills(org.mockito.ArgumentMatchers.any()))
        .willThrow(new ResourceAccessException("연결 실패"));

    SkillSummaryQueryService.SkillSummary summary = service.find(USER_ID);

    assertThat(summary.skills()).isEmpty();
    assertThat(summary.totalAttemptCount()).isEqualTo(184);
  }

  @Test
  void AI가_준_순서를_지킨다() {
    // 약한 것부터 정렬해 온다. 백엔드가 다시 정렬하면 두 곳이 갈린다
    givenMemories(23L);
    given(fastApiClient.summarizeSkills(org.mockito.ArgumentMatchers.any()))
        .willReturn(
            new LearningSkillSummaryFastApiResponse(
                List.of(new SkillMastery(2L, 0.2, "더 연습해요"), new SkillMastery(1L, 0.9, "익숙해요"))));
    given(learningSkillRepository.findAllById(org.mockito.ArgumentMatchers.anySet()))
        .willReturn(List.of(skill(1L, "하나"), skill(2L, "둘")));

    assertThat(service.find(USER_ID).skills())
        .extracting(SkillSummaryQueryService.SkillMasteryView::learningSkillId)
        .containsExactly(2L, 1L);
  }
}
