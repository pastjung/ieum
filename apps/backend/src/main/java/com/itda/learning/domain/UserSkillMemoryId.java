package com.itda.learning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** user_skill_memory의 복합 PK (user_id, skill_id) — ERD 5.4절. 사용자·요소별 현재 상태 한 행이다. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSkillMemoryId implements Serializable {

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "skill_id", nullable = false)
  private Long skillId;

  private UserSkillMemoryId(Long userId, Long skillId) {
    this.userId = userId;
    this.skillId = skillId;
  }

  public static UserSkillMemoryId of(Long userId, Long skillId) {
    if (userId == null || skillId == null) {
      throw new IllegalArgumentException("사용자와 학습 요소는 모두 필수입니다.");
    }
    return new UserSkillMemoryId(userId, skillId);
  }
}
