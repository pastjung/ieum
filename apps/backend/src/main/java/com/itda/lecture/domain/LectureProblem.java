package com.itda.lecture.domain;

import com.itda.problem.domain.Problem;
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

  /** 연결한 문제. problem_id FK로 단방향 다대일 참조한다. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "problem_id", nullable = false)
  private Problem problem;

  public LectureProblem(Lecture lecture, Problem problem) {
    if (lecture == null) {
      throw new IllegalArgumentException("수업은 필수입니다.");
    }
    if (problem == null) {
      throw new IllegalArgumentException("문제는 필수입니다.");
    }
    this.lecture = lecture;
    this.problem = problem;
  }
}
