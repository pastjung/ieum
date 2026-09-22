package com.itda.problem.domain;

import com.itda.learning.domain.UserSkillMemory;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "user_skill_error_counts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSkillErrorCount {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne
  @JoinColumn(name = "user_skill_memory_id")
  UserSkillMemory userSkillMemory;

  @Enumerated(EnumType.STRING)
  @Column(name = "error_type", nullable = false, length = 20)
  private ErrorType errorType;

  @Column(name = "error_count", nullable = false)
  private Long errorCount;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false, updatable = false)
  private LocalDateTime updatedAt;

  public UserSkillErrorCount(
      UserSkillMemory userSkillMemory, ErrorType errorType, Long errorCount) {
    this.userSkillMemory = userSkillMemory;
    this.errorType = errorType;
    this.errorCount = errorCount;
  }
}
