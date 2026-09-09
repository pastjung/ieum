package com.itda.problem.domain;

import com.itda.global.braille.BraillePattern;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * problem_cells — 문제의 정답 점형을 Cell 위치별로 저장한다 (ERD 4.4절).
 *
 * <p>Problem 애그리거트에 속한다. 문제 본문과 함께 한 트랜잭션으로 등록하며 문제 불변 원칙을 따른다 — 등록 후 수정·삭제하지 않는다.
 *
 * <p>cell_index는 1부터 시작한다. 연속성과 최대 6개 검증은 애그리거트 루트인 Problem이 수행한다.
 */
@Entity
@Table(
    name = "problem_cells",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_problem_cells_problem_cell_index",
            columnNames = {"problem_id", "cell_index"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemCell {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "problem_id", nullable = false)
  private Problem problem;

  @Column(name = "cell_index", nullable = false)
  private int cellIndex;

  /** 서버 채점의 기준이 되는 정답 6점 패턴. */
  @Embedded
  @AttributeOverride(
      name = "value",
      column = @Column(name = "expected_pattern", nullable = false, columnDefinition = "TINYINT"))
  private BraillePattern expectedPattern;

  ProblemCell(Problem problem, int cellIndex, BraillePattern expectedPattern) {
    if (problem == null) {
      throw new IllegalArgumentException("문제는 필수입니다.");
    }
    if (cellIndex < 1) {
      throw new IllegalArgumentException("Cell 순번은 1 이상이어야 합니다: " + cellIndex);
    }
    if (expectedPattern == null) {
      throw new IllegalArgumentException("정답 패턴은 필수입니다.");
    }
    this.problem = problem;
    this.cellIndex = cellIndex;
    this.expectedPattern = expectedPattern;
  }
}
