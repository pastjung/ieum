package com.itda.learning.domain;

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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * attempt_cells — 한 제출에서 Cell별로 정답과 입력을 비교한 결과 (ERD 5.2절).
 *
 * <p>attempt_id FK를 단방향 {@link jakarta.persistence.ManyToOne}으로 소유한다. ProblemAttempt가 Cell 컬렉션을 갖지
 * 않으므로 저장은 Attempt를 저장한 뒤 호출하는 서비스가 직접 한다. diff_mask는 서버가 expected XOR input으로 계산하므로 이 클래스에는 값을
 * 주입하는 경로가 없다.
 */
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

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "attempt_id", nullable = false)
  private ProblemAttempt attempt;

  @Column(name = "cell_index", nullable = false)
  private int cellIndex;

  /** 서버가 문제 정답에서 복사한 패턴. */
  @Embedded
  @AttributeOverride(
      name = "value",
      column = @Column(name = "expected_pattern", nullable = false, columnDefinition = "TINYINT"))
  private BraillePattern expectedPattern;

  /** 사용자가 명시한 패턴. 0은 빈 점형이며 누락 Cell과 구분한다. */
  @Embedded
  @AttributeOverride(
      name = "value",
      column = @Column(name = "input_pattern", nullable = false, columnDefinition = "TINYINT"))
  private BraillePattern inputPattern;

  /** expected XOR input. 서버 계산값이다. */
  @Embedded
  @AttributeOverride(
      name = "value",
      column = @Column(name = "diff_mask", nullable = false, columnDefinition = "TINYINT"))
  private BraillePattern diffMask;

  /** 해당 Attempt의 attempted_at과 동일한 최종 접수 시각 (ERD 5.2절). */
  @Column(name = "responded_at", nullable = false)
  private LocalDateTime respondedAt;

  public AttemptCell(
      ProblemAttempt attempt, int cellIndex, CellSubmission submission, LocalDateTime now) {
    if (attempt == null) {
      throw new IllegalArgumentException("풀이는 필수입니다.");
    }
    if (cellIndex < 1) {
      throw new IllegalArgumentException("Cell 순번은 1 이상이어야 합니다: " + cellIndex);
    }
    this.attempt = attempt;
    this.cellIndex = cellIndex;
    this.expectedPattern = submission.expectedPattern();
    this.inputPattern = submission.inputPattern();
    this.diffMask = submission.expectedPattern().diff(submission.inputPattern());
    this.respondedAt = now;
  }

  public boolean isCorrect() {
    return diffMask.isEmpty();
  }

  /** 정답에는 있지만 입력에는 없는 점 위치의 마스크. 원본 두 점형에서 계산하므로 별도 컬럼으로 저장하지 않는다. */
  public BraillePattern getMissingMask() {
    return expectedPattern.missingIn(inputPattern);
  }

  /** 입력에는 있지만 정답에는 없는 점 위치의 마스크. 원본 두 점형에서 계산하므로 별도 컬럼으로 저장하지 않는다. */
  public BraillePattern getExtraMask() {
    return expectedPattern.extraIn(inputPattern);
  }
}
