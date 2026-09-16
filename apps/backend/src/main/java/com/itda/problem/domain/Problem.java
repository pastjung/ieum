package com.itda.problem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "problems")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Problem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 문제를 등록한 users.id. 공개 INSTRUCTOR 문제는 강사 ID이며 SYSTEM·AI는 NULL이다. */
  @Column(name = "creator_user_id")
  private Long creatorUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 20)
  private ProblemSourceType sourceType;

  @Enumerated(EnumType.STRING)
  @Column(name = "problem_type", nullable = false, length = 20)
  private ProblemType problemType;

  /** 문자 문제의 한글 대상, 또는 점 위치·조합 문제의 사람이 읽을 수 있는 목표 설명. 정답 점형은 Cell에 저장한다. */
  @Column(name = "target_text", nullable = false, columnDefinition = "TEXT")
  private String targetText;

  /** 등록 시각. Hibernate가 INSERT 시점에 채우므로 생성자에서 받지 않는다. */
  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  public Problem(
      Long creatorUserId,
      ProblemSourceType sourceType,
      ProblemType problemType,
      String targetText) {

    this.creatorUserId = creatorUserId;
    this.sourceType = sourceType;
    this.problemType = problemType;
    this.targetText = targetText;
  }
}
