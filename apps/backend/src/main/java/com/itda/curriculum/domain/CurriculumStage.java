package com.itda.curriculum.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * curriculum_stages — 커리큘럼 단계 정의 (ERD 4.1절). MVP 범위는 L0~L6이며 L7·L8은 후속이다.
 *
 * <p>단계와 학습 요소는 서로 다른 개념이다. L0는 공통 안내이며 개인 학습 세션과 users.level 값에서 제외한다.
 */
@Entity
@Table(name = "curriculum_stages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CurriculumStage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 100)
  private String name;

  /** 단계 코드 (L0~L6). 전체에서 고유하며 users.level이 이 값을 참조한다. */
  @Column(nullable = false, unique = true, length = 30)
  private String level;

  /**
   * 단계 학습 순서. 단계 사이에서 고유하며 강사의 사전 문제 순서와 무관하다.
   *
   * <p>L0도 curriculum_stages의 한 행이므로(ERD 4.1절) 순번은 0부터 시작한다. 1 이상으로 조이면 L0 행만 단계 코드와 순번이 어긋난다.
   */
  @Column(name = "order_no", nullable = false, unique = true)
  private int orderNo;

  @Column(columnDefinition = "TEXT")
  private String description;

  private CurriculumStage(String name, String level, int orderNo, String description) {
    this.name = requireText(name, "단계 이름");
    this.level = requireText(level, "단계 코드");
    // L0가 0번이므로 하한은 1이 아니라 0이다 (ERD 4.1절).
    if (orderNo < 0) {
      throw new IllegalArgumentException("단계 순서는 0 이상이어야 합니다: " + orderNo);
    }
    this.orderNo = orderNo;
    this.description = description;
  }

  public static CurriculumStage of(String name, String level, int orderNo, String description) {
    return new CurriculumStage(name, level, orderNo, description);
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
    }
    return value;
  }
}
