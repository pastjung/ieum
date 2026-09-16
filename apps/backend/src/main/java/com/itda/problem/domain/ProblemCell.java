package com.itda.problem.domain;

import com.itda.global.braille.BraillePattern;
import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
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
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

  /**
 * problem_cells — 문제의 정답 점형을 Cell 위치별로 저장한다 (ERD 4.4절).
 *
 * <p>Problem을 단방향 다대일로 참조한다 — Problem은 Cell 목록을 들고 있지 않다. 문제 본문과 함께 한 트랜잭션으로 등록하며 문제 불변 원칙을 따른다 — 등록
 * 후 수정·삭제하지 않는다.
 *
 * <p>cell_index는 1부터 시작한다. 순번의 연속성은 검증이 아니라 생성 방식으로 보장한다 — {@link #createAll}에 넘긴 정답 패턴 목록의 순서가 곧
 * 1부터의 순번이다. Cell 개수 검증도 같은 곳에서 수행한다.
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

  @Column(name = "expected_pattern", nullable = false, columnDefinition = "TINYINT")
  private int expectedPattern;

  public ProblemCell(Problem problem, int cellIndex, int expectedPattern) {
    this.problem = problem;
    this.cellIndex = cellIndex;
    this.expectedPattern = expectedPattern;
  }
}
