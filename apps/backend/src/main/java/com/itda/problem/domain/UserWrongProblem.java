package com.itda.problem.domain;

import com.itda.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "user_wrong_problems",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_user_wrong_problem_user_problem",
            columnNames = {"user_id", "problem_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserWrongProblem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "problem_id", nullable = false)
  private Problem problem;

  @Enumerated(EnumType.STRING)
  @Column(name = "level", nullable = false)
  private Level level;

  @Column(name = "is_completed")
  private boolean isCompleted;

  public UserWrongProblem(User user, Problem problem, Level level) {
    this.user = user;
    this.problem = problem;
    this.level = level;
  }

  /** 다시 맞혔다 — 학습하기의 오답 재출제 후보에서 빠진다 */
  public void complete() {
    this.isCompleted = true;
  }

  /** 해소됐던 문제를 다시 틀렸다 — 오답 재출제 후보로 되돌린다 */
  public void reopen() {
    this.isCompleted = false;
  }
}
