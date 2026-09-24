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
      User user,
      LearningSkill skill,
      int totalSeen,
      int totalCorrect,
      LocalDateTime lastPracticedAt,
      Double masteryProbability) {
    this.user = user;
    this.skill = skill;
    applyAnalysisSnapshot(totalSeen, totalCorrect, lastPracticedAt, masteryProbability);
  }

  public void applyAnalysisSnapshot(
      int totalSeen,
      int totalCorrect,
      LocalDateTime lastPracticedAt,
      Double masteryProbability) {
    if (totalSeen < 0 || totalCorrect < 0 || totalCorrect > totalSeen) {
      throw new IllegalArgumentException("학습 요소 누적 횟수가 올바르지 않습니다.");
    }
    if (lastPracticedAt == null) {
      throw new IllegalArgumentException("마지막 학습 시각은 필수입니다.");
    }
    if (masteryProbability == null || masteryProbability < 0 || masteryProbability > 1) {
      throw new IllegalArgumentException("숙련도 확률은 0 이상 1 이하여야 합니다.");
    }
    this.totalSeen = totalSeen;
    this.totalCorrect = totalCorrect;
    this.totalIncorrect = totalSeen - totalCorrect;
    this.lastPracticedAt = lastPracticedAt;
    this.masteryProbability = masteryProbability;
  }
}
