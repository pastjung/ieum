package com.itda.learning.domain;

import com.itda.problem.domain.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "attempt_skill_result_errors",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_attempt_skill_result_errors_result_type",
            columnNames = {"attempt_skill_result_id", "error_type"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttemptSkillResultError {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "attempt_skill_result_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_attempt_skill_result_errors_result"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private AttemptSkillResult attemptSkillResult;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.ENUM)
  @Column(name = "error_type", nullable = false)
  private ErrorType errorType;

  @Column(name = "error_count", nullable = false)
  private int errorCount;

  public AttemptSkillResultError(
      AttemptSkillResult attemptSkillResult, ErrorType errorType, int errorCount) {
    if (attemptSkillResult == null) {
      throw new IllegalArgumentException("풀이 요소 결과는 필수입니다.");
    }
    if (errorType == null) {
      throw new IllegalArgumentException("오류 유형은 필수입니다.");
    }
    if (errorCount <= 0) {
      throw new IllegalArgumentException("오류 횟수는 1 이상이어야 합니다.");
    }
    this.attemptSkillResult = attemptSkillResult;
    this.errorType = errorType;
    this.errorCount = errorCount;
  }
}
