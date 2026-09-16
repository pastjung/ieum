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
import org.hibernate.annotations.CreationTimestamp;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * problems — 검증을 통과한 문제 원본 (ERD 4.3절). 수업·커리큘럼·추천학습이 공통으로 참조한다.
 *
 * <p>등록 후 수정·삭제는 제공하지 않는다. 다른 내용은 새 문제로 등록한다. 그래서 상태 변경 메서드가 없다.
 *
 * <p>정답 Cell은 {@link ProblemCell}이 problem_id로 이 문제를 참조하는 단방향 다대일이다. Problem은 Cell 목록을 들고 있지 않으므로 Cell이
 * 필요한 쪽에서 {@code ProblemCellRepository}로 조회한다.
 *
 * <p>ERD 4.3절이 "problems·problem_cells를 전체 검증 후 한 트랜잭션으로 저장하고 실패 시 일부도 저장하지 않는다"고 규정하므로, 문제와 Cell 저장은 한
 * 트랜잭션 안에서 함께 수행한다.
 */
@Entity
@Table(name = "problems")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Problem {

  /** ERD 4.4절: 문제는 1~6 Cell이다. Cell 개수 검증은 {@link ProblemCell#createAll}이 수행한다. */
  public static final int MIN_CELLS = 1;

  public static final int MAX_CELLS = 6;

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
    if (sourceType == null || problemType == null) {
      throw new IllegalArgumentException("출처와 문제 유형은 필수입니다.");
    }
    if (targetText == null || targetText.isBlank()) {
      throw new IllegalArgumentException("목표 텍스트는 필수입니다.");
    }

    this.creatorUserId = creatorUserId;
    this.sourceType = sourceType;
    this.problemType = problemType;
    this.targetText = targetText;
  }

  /** 강사가 공개 등록하는 문제. source_type·creator_user_id는 서버가 결정한다 (ERD 4.3절). */
  public static Problem registerByInstructor(
      Long creatorUserId, ProblemType problemType, String targetText) {
    if (creatorUserId == null) {
      throw new IllegalArgumentException("강사 등록 문제는 작성자가 필수입니다.");
    }
    return new Problem(creatorUserId, ProblemSourceType.INSTRUCTOR, problemType, targetText);
  }

  /** 버전 Seed·일회성 마이그레이션으로 등록하는 기본 문제. 작성자는 NULL이다 (ERD 4.7절). */
  public static Problem registerAsSystem(ProblemType problemType, String targetText) {
    return new Problem(null, ProblemSourceType.SYSTEM, problemType, targetText);
  }

}
