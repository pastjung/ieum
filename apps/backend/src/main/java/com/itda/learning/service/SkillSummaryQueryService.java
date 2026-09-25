package com.itda.learning.service;

import com.itda.global.FastApiClient;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.learning.domain.UserSkillMemory;
import com.itda.learning.dto.LearningSkillStatePayload;
import com.itda.learning.dto.LearningSkillSummaryFastApiRequest;
import com.itda.learning.dto.LearningSkillSummaryFastApiResponse;
import com.itda.problem.domain.LearningSkill;
import com.itda.problem.repository.LearningSkillRepository;
import com.itda.problem.repository.UserSkillMemoryRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

/**
 * "내 실력" 화면과 홈 복습 카드가 읽는 숙련 상태.
 *
 * <p>지금까지 숙련 라벨은 세션 완료 응답({@code weakSkills})에만 실렸다. 그래서 두 화면은 세션 밖에서 알 방법이 없어 고정값("모음 ㅏ·ㅓ 익숙해요",
 * "연속 학습 7일", "정답률 82%")을 내 실력처럼 보여주고 있었다.
 *
 * <p>숙련 라벨의 기준({@code label_of})과 정렬은 FastAPI에 둔다 — 문턱값을 여기 베끼면 세션 요약과 이 화면이 서로 다른 말을 한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillSummaryQueryService {

  private final UserSkillMemoryRepository userSkillMemoryRepository;
  private final LearningSkillRepository learningSkillRepository;
  private final ProblemAttemptRepository problemAttemptRepository;
  private final FastApiClient fastApiClient;

  @Transactional(readOnly = true)
  public SkillSummary find(long userId) {
    long total = problemAttemptRepository.countByUserId(userId);
    long correct = problemAttemptRepository.countByUserIdAndCorrectTrue(userId);
    return new SkillSummary(
        Math.toIntExact(total), Math.toIntExact(correct), masteryViews(userId));
  }

  /**
   * 요소별 숙련 상태. AI가 응답하지 않으면 빈 목록이다.
   *
   * <p>숙련 라벨이 없다고 화면 전체를 실패시키지 않는다 — 그러면 풀이 기록까지 볼 수 없게 된다.
   */
  private List<SkillMasteryView> masteryViews(long userId) {
    List<UserSkillMemory> memories = userSkillMemoryRepository.findAllByUser_Id(userId);
    if (memories.isEmpty()) {
      return List.of();
    }
    LearningSkillSummaryFastApiResponse response;
    try {
      response =
          fastApiClient.summarizeSkills(
              new LearningSkillSummaryFastApiRequest(
                  memories.stream().map(SkillSummaryQueryService::toPayload).toList()));
    } catch (RestClientException exception) {
      log.warn("FastAPI skill summary request failed", exception);
      return List.of();
    }
    if (response == null) {
      return List.of();
    }

    Set<Long> skillIds =
        response.skills().stream()
            .map(LearningSkillSummaryFastApiResponse.SkillMastery::learningSkillId)
            .collect(Collectors.toSet());
    Map<Long, LearningSkill> skillsById =
        learningSkillRepository.findAllById(skillIds).stream()
            .collect(Collectors.toMap(LearningSkill::getId, Function.identity()));

    // AI가 약한 것부터 정렬해 보낸 순서를 지킨다. 여기서 다시 정렬하면 두 곳이 갈린다
    return response.skills().stream()
        .map(
            mastery -> {
              LearningSkill skill = skillsById.get(mastery.learningSkillId());
              // 이름을 찾지 못한 요소는 내보내지 않는다 — 숙련도만 보여주면 학습자는
              // 무엇이 약한지 알 수 없다
              return skill == null
                  ? null
                  : new SkillMasteryView(
                      skill.getId(), skill.getName(), mastery.masteryProb(), mastery.label());
            })
        .filter(Objects::nonNull)
        .toList();
  }

  private static LearningSkillStatePayload toPayload(UserSkillMemory memory) {
    return new LearningSkillStatePayload(
        memory.getSkill().getId(),
        memory.getTotalSeen(),
        memory.getTotalCorrect(),
        memory.getLastPracticedAt(),
        memory.getMasteryProbability(),
        Map.of());
  }

  public record SkillSummary(
      int totalAttemptCount, int correctAttemptCount, List<SkillMasteryView> skills) {
    public SkillSummary {
      skills = List.copyOf(skills);
    }
  }

  public record SkillMasteryView(
      Long learningSkillId, String name, Double masteryProb, String label) {}
}
