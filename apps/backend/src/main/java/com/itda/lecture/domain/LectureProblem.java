package com.itda.lecture.domain;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * lecture_problems — 수업에 연결된 문제 (ERD 3.4절). 출제 이벤트 이력 테이블이 아니다.
 *
 * <p>같은 수업·문제 쌍은 한 번만 연결한다. 사전 순서 컬럼·실제 출제 시각·출제 이벤트 이력은 두지 않는다.
 *
 * <p>Lecture 애그리거트에 속하지만 역방향 @OneToMany는 두지 않는다. 수업 문제 수에 상한이 없고 ERD가 CLOSED에서 연결 삭제를 금지하므로
 * cascade·orphanRemoval의 이득이 없다.
 */
@Entity
@Table(
    name = "lecture_problems",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_lecture_problems_lecture_problem",
            columnNames = {"lecture_id", "problem_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureProblem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "lecture_id", nullable = false)
  private Lecture lecture;

  /** problems.id. problem 컨텍스트를 참조하므로 Long으로 둔다. */
  @Column(name = "problem_id", nullable = false)
  private Long problemId;

  private LectureProblem(Lecture lecture, Long problemId) {
    if (lecture == null) {
      throw new IllegalArgumentException("수업은 필수입니다.");
    }
    if (problemId == null) {
      throw new IllegalArgumentException("문제는 필수입니다.");
    }
    this.lecture = lecture;
    this.problemId = problemId;
  }

  /**
   * 수업에 문제를 연결한다. SCHEDULED·OPEN에서만 가능하다 (ERD 3.4절).
   *
   * <p>CLOSED에서는 연결·해제·전달을 모두 금지한다.
   */
  public static LectureProblem link(Lecture lecture, Long problemId) {
    if (lecture != null && lecture.getStatus() == LectureStatus.CLOSED) {
      throw new IllegalStateException("종료된 수업에는 문제를 연결할 수 없습니다.");
    }
    return new LectureProblem(lecture, problemId);
  }
}
