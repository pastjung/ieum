package com.itda.learning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 문제 풀이 시도에서 셀별 정답 패턴과 입력 패턴의 비교 결과를 저장한다. */
@Entity
@Table(
    name = "attempt_cells",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_attempt_cells_attempt_cell_index",
            columnNames = {"attempt_id", "cell_index"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttemptCell {

  private static final int MAX_PATTERN = 0b111111;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "attempt_id", nullable = false)
  private ProblemAttempt attempt;

  @Column(name = "cell_index", nullable = false)
  private int cellIndex;

  @Column(name = "expected_pattern", nullable = false, columnDefinition = "TINYINT")
  private int expectedPattern;

  @Column(name = "input_pattern", nullable = false, columnDefinition = "TINYINT")
  private int inputPattern;

  /** 정답과 입력에서 서로 다른 점을 나타내는 XOR 비트 마스크다. */
  @Column(name = "diff_mask", nullable = false, columnDefinition = "TINYINT")
  private int diffMask;

  @Column(name = "responded_at", nullable = false)
  private LocalDateTime respondedAt;

  public AttemptCell(
      ProblemAttempt attempt,
      int cellIndex,
      int expectedPattern,
      int inputPattern,
      LocalDateTime respondedAt) {
    if (attempt == null) {
      throw new IllegalArgumentException("풀이 시도는 필수입니다.");
    }
    if (cellIndex < 1) {
      throw new IllegalArgumentException("셀 순서는 1 이상이어야 합니다: " + cellIndex);
    }
    validatePattern(expectedPattern);
    validatePattern(inputPattern);
    if (respondedAt == null) {
      throw new IllegalArgumentException("응답 시각은 필수입니다.");
    }

    this.attempt = attempt;
    this.cellIndex = cellIndex;
    this.expectedPattern = expectedPattern;
    this.inputPattern = inputPattern;
    this.diffMask = expectedPattern ^ inputPattern;
    this.respondedAt = respondedAt;
  }

  public boolean isCorrect() {
    return diffMask == 0;
  }

  private static void validatePattern(int pattern) {
    if (pattern < 0 || pattern > MAX_PATTERN) {
      throw new IllegalArgumentException("점자 패턴은 0부터 63 사이여야 합니다: " + pattern);
    }
  }
}
