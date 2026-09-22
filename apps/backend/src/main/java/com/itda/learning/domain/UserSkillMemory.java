package com.itda.learning.domain;

import com.itda.problem.domain.LearningSkill;
import com.itda.user.domain.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(
    name = "user_skill_memory",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_user_skill_memory_problem_skill",
            columnNames = {"user_id", "skill_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSkillMemory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @ManyToOne
  @JoinColumn(name = "skill_id", nullable = false)
  private LearningSkill skill;

  @Column(name = "total_seen", nullable = false)
  private int totalSeen;

  @Column(name = "total_correct", nullable = false)
  private int totalCorrect;

  @Column(name = "total_incorrect", nullable = false)
  private int totalIncorrect;

  @Column(name = "last_practiced_at")
  private LocalDateTime lastPracticedAt;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @Column(name = "mastery_probability", nullable = false)
  private Double masteryProbability;

  public UserSkillMemory(
      User user, LearningSkill skill, int totalSeen, int totalCorrect, int totalIncorrect) {
    this.user = user;
    this.skill = skill;
    this.totalSeen = totalSeen;
    this.totalCorrect = totalCorrect;
    this.totalIncorrect = totalIncorrect;
  }
}
