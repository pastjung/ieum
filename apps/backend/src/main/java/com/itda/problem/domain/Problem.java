package com.itda.problem.domain;

import com.itda.global.braille.BraillePattern;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * problems — 검증을 통과한 문제 원본 (ERD 4.3절). 수업·커리큘럼·추천학습이 공통으로 참조한다.
 *
 * <p>등록 후 수정·삭제는 제공하지 않는다. 다른 내용은 새 문제로 등록한다. 그래서 상태 변경 메서드가 없다.
 *
 * <p>애그리거트 루트로서 정답 Cell을 함께 소유한다. ERD 4.3절이 "problems·problem_cells를 전체 검증 후 한 트랜잭션으로 저장하고 실패 시 일부도
 * 저장하지 않는다"고 규정하므로 cascade·orphanRemoval이 그 계약과 일치한다.
 *
 * <p>Cell 순번의 연속성은 검증이 아니라 생성 방식으로 보장한다 — 정답 패턴 목록의 순서가 곧 1부터의 순번이다.
 */
@Entity
@Table(
    name = "problems",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_problems_creator_idempotency_key",
            columnNames = {"creator_user_id", "creation_idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Problem {

  /** ERD 4.4절: 문제는 1~6 Cell이다. */
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

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /** 강사 문제 등록의 재전송 식별 UUID v4. SYSTEM·AI는 NULL이다. */
  @Column(name = "creation_idempotency_key", columnDefinition = "CHAR(36)")
  private String creationIdempotencyKey;

  @OneToMany(
      mappedBy = "problem",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @OrderBy("cellIndex ASC")
  private List<ProblemCell> cells = new ArrayList<>();

  private Problem(
      Long creatorUserId,
      ProblemSourceType sourceType,
      ProblemType problemType,
      String targetText,
      String creationIdempotencyKey,
      List<Integer> expectedPatterns,
      LocalDateTime now) {
    if (sourceType == null || problemType == null) {
      throw new IllegalArgumentException("출처와 문제 유형은 필수입니다.");
    }
    if (targetText == null || targetText.isBlank()) {
      throw new IllegalArgumentException("목표 텍스트는 필수입니다.");
    }
    if (now == null) {
      throw new IllegalArgumentException("등록 시각은 필수입니다.");
    }
    requireValidCellCount(problemType, expectedPatterns);

    this.creatorUserId = creatorUserId;
    this.sourceType = sourceType;
    this.problemType = problemType;
    this.targetText = targetText;
    this.creationIdempotencyKey = creationIdempotencyKey;
    this.createdAt = now;
    for (int index = 0; index < expectedPatterns.size(); index++) {
      this.cells.add(
          new ProblemCell(this, index + 1, BraillePattern.of(expectedPatterns.get(index))));
    }
  }

  /**
   * 강사가 공개 등록하는 문제. source_type·creator_user_id는 서버가 결정한다 (ERD 4.3절).
   *
   * @param expectedPatterns Cell 순서대로 나열한 0~63 정답 패턴. 목록 순서가 cell_index 1..N이 된다
   * @param creationIdempotencyKey 같은 강사·같은 키의 재전송을 식별하는 UUID v4
   */
  public static Problem registerByInstructor(
      Long creatorUserId,
      String creationIdempotencyKey,
      ProblemType problemType,
      String targetText,
      List<Integer> expectedPatterns,
      LocalDateTime now) {
    if (creatorUserId == null) {
      throw new IllegalArgumentException("강사 등록 문제는 작성자가 필수입니다.");
    }
    if (creationIdempotencyKey == null || creationIdempotencyKey.isBlank()) {
      throw new IllegalArgumentException("강사 등록 문제는 멱등 키가 필수입니다.");
    }
    return new Problem(
        creatorUserId,
        ProblemSourceType.INSTRUCTOR,
        problemType,
        targetText,
        creationIdempotencyKey,
        expectedPatterns,
        now);
  }

  /** 버전 Seed·일회성 마이그레이션으로 등록하는 기본 문제. 작성자·멱등 키는 NULL이다 (ERD 4.7절). */
  public static Problem registerAsSystem(
      ProblemType problemType,
      String targetText,
      List<Integer> expectedPatterns,
      LocalDateTime now) {
    return new Problem(
        null, ProblemSourceType.SYSTEM, problemType, targetText, null, expectedPatterns, now);
  }

  /** Backend 내부 후보 생성·검증을 거쳐 등록하는 AI 문제. 작성자·멱등 키는 NULL이다 (ERD 4.3절). */
  public static Problem registerByAi(
      ProblemType problemType,
      String targetText,
      List<Integer> expectedPatterns,
      LocalDateTime now) {
    return new Problem(
        null, ProblemSourceType.AI, problemType, targetText, null, expectedPatterns, now);
  }

  /** 정답 Cell 목록. 수정 불가 뷰로 반환해 애그리거트 밖에서 구성을 바꿀 수 없게 한다. */
  public List<ProblemCell> getCells() {
    return Collections.unmodifiableList(cells);
  }

  public int cellCount() {
    return cells.size();
  }

  private static void requireValidCellCount(
      ProblemType problemType, List<Integer> expectedPatterns) {
    if (expectedPatterns == null || expectedPatterns.isEmpty()) {
      throw new IllegalArgumentException("정답 Cell은 최소 " + MIN_CELLS + "개여야 합니다.");
    }
    if (expectedPatterns.size() > MAX_CELLS) {
      throw new IllegalArgumentException(
          "정답 Cell은 최대 " + MAX_CELLS + "개여야 합니다: " + expectedPatterns.size());
    }
    if (problemType.requiresSingleCell() && expectedPatterns.size() != 1) {
      throw new IllegalArgumentException(
          problemType + " 문제는 정확히 1 Cell이어야 합니다: " + expectedPatterns.size());
    }
  }
}
