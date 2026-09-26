package com.itda.problem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * learning_skills — 학습 요소 사전 (ERD 4.5절).
 *
 * <p>같은 의미의 요소는 하나로 정의하며 반복 연습마다 새 행을 만들지 않는다. 예를 들어 '초성 ㄱ' 정의는 한 행이고 여러 문제·평가가 그 ID를 반복 참조한다.
 *
 * <p>요소를 식별하는 키는 (skill_type, metadata) 조합이며 DB 유니크 제약으로 보장한다. 같은 유형 안에서 같은 metadata를 가진 요소는 하나뿐이다.
 */
@Entity
@Table(
    name = "learning_skills",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_learning_skills_skill_type_metadata",
            columnNames = {"skill_type", "metadata"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningSkill {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "skill_type", nullable = false, length = 20)
  private SkillType skillType;

  /** 유형 안에서 요소를 구분하는 값. skill_type과 묶여 유일하다. */
  @Column(name = "metadata", nullable = false, length = 10)
  private String metadata;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(columnDefinition = "TEXT")
  private String description;

  private LearningSkill(SkillType skillType, String metadata, String name, String description) {
    if (skillType == null) {
      throw new IllegalArgumentException("요소 유형은 필수입니다.");
    }
    this.skillType = skillType;
    this.metadata = requireText(metadata, "요소 metadata");
    this.name = requireText(name, "요소 이름");
    this.description = description;
  }

  public static LearningSkill of(
      SkillType skillType, String metadata, String name, String description) {
    return new LearningSkill(skillType, metadata, name, description);
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
    }
    return value;
  }
}
