package com.itda.problem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * learning_skills — 학습 요소 사전 (ERD 4.5절).
 *
 * <p>같은 의미의 요소는 하나로 정의하며 반복 연습마다 새 행을 만들지 않는다. 예를 들어 '초성 ㄱ' 정의는 한 행이고 여러 문제·평가가 그 ID를 반복 참조한다.
 *
 * <p>skill_code는 유형과 무관하게 전체에서 고유하다.
 */
@Entity
@Table(name = "learning_skills")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningSkill {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "skill_type", nullable = false, length = 20)
  private SkillType skillType;

  @Column(name = "skill_code", nullable = false, unique = true, length = 100)
  private String skillCode;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(columnDefinition = "TEXT")
  private String description;

  private LearningSkill(SkillType skillType, String skillCode, String name, String description) {
    if (skillType == null) {
      throw new IllegalArgumentException("요소 유형은 필수입니다.");
    }
    this.skillType = skillType;
    this.skillCode = requireText(skillCode, "요소 코드");
    this.name = requireText(name, "요소 이름");
    this.description = description;
  }

  public static LearningSkill of(
      SkillType skillType, String skillCode, String name, String description) {
    return new LearningSkill(skillType, skillCode, name, description);
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
    }
    return value;
  }
}
