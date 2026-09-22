package com.itda.problem.domain;

import com.itda.learning.domain.LearningSession;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "problem_sessions",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_problem_sessions_problem_session",
            columnNames = {"problem_id", "session_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemSession {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "problem_id", nullable = false)
  private Problem problem;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "session_id", nullable = false)
  private LearningSession session;

  @Column(name = "completed", nullable = false)
  private boolean completed = false;

  public ProblemSession(Problem problem, LearningSession session) {
    this.problem = problem;
    this.session = session;
  }

  public void complete() {
    this.completed = true;
  }
}
