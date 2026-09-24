package com.itda.learning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.problem.domain.ErrorType;
import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.SkillType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AttemptSkillResultTest {

  private final ProblemAttempt attempt =
      ProblemAttempt.forFreeSession(
          1L, 2L, 3L, 1, 4L, false, LocalDateTime.of(2026, 9, 25, 10, 0));
  private final LearningSkill skill =
      LearningSkill.of(SkillType.SKILL_03, "ㅇ", "받침 이응", null);

  @Test
  void 정답은_오류_횟수가_0이어야_한다() {
    AttemptSkillResult result = new AttemptSkillResult(attempt, skill, true, 0);

    assertThat(result.isCorrect()).isTrue();
    assertThat(result.getErrorCount()).isZero();
  }

  @Test
  void 오답은_오류_횟수가_1_이상이어야_한다() {
    assertThatThrownBy(() -> new AttemptSkillResult(attempt, skill, false, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 하나의_요소_결과에_여러_오류_유형을_연결할_수_있다() {
    AttemptSkillResult result = new AttemptSkillResult(attempt, skill, false, 4);

    AttemptSkillResultError missing =
        new AttemptSkillResultError(result, ErrorType.DOT_MISSING, 1);
    AttemptSkillResultError mirror =
        new AttemptSkillResultError(result, ErrorType.MIRROR, 3);

    assertThat(missing.getAttemptSkillResult()).isSameAs(result);
    assertThat(mirror.getAttemptSkillResult()).isSameAs(result);
    assertThat(missing.getErrorCount() + mirror.getErrorCount()).isEqualTo(result.getErrorCount());
  }
}
