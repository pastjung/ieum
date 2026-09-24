package com.itda.learning.domain;

import com.itda.curriculum.domain.CurriculumStage;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "learning_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningSession {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @ManyToOne
  @JoinColumn(name = "curriculum_stage_id")
  private CurriculumStage curriculumStage;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  public LearningSession(Long userId, CurriculumStage curriculumStage) {
    this.userId = userId;
    this.curriculumStage = curriculumStage;
  }

  public void complete(LocalDateTime completedAt) {
    if (this.completedAt != null) {
      throw new IllegalStateException("이미 완료된 학습 세션입니다.");
    }
    if (completedAt == null) {
      throw new IllegalArgumentException("완료 시각은 필수입니다.");
    }
    this.completedAt = completedAt;
  }
}
