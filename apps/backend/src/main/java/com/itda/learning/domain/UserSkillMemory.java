package com.itda.learning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * user_skill_memory — 사용자·학습 요소별 <b>현재 상태</b> (ERD 5.4절). 원본 평가 이력을 대체하지 않는다.
 *
 * <p>행이 없으면 미관측이며, 완료된 개인 학습 세션의 첫 유효 요소 관측을 분석할 때 생성한다. 조회만으로 상태를 갱신하지 않는다.
 *
 * <p>BKT 확률 계산 자체는 백엔드 버전 관리 설정(BKT_V1 파라미터)에 있으므로 서비스가 수행하고 결과를 넘긴다. 이 엔티티는 카운터 불변식 (total_seen =
 * total_correct + total_incorrect)과 "error_count만큼 오답 카운터를 늘리지 않는다"는 규칙을 강제한다.
 */
@Entity
@Table(name = "user_skill_memory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSkillMemory {

  @EmbeddedId private UserSkillMemoryId id;

  /** 유효 요소 관측 수. total_correct + total_incorrect와 항상 같다. */
  @Column(name = "total_seen", nullable = false)
  private int totalSeen;

  @Column(name = "total_correct", nullable = false)
  private int totalCorrect;

  @Column(name = "total_incorrect", nullable = false)
  private int totalIncorrect;

  /** 해당 요소의 마지막 유효 Attempt attempted_at. */
  @Column(name = "last_practiced_at")
  private LocalDateTime lastPracticedAt;

  /** HLR 확장 예약. MVP에서는 NULL이며 계산하지 않는다 (ERD 8절). */
  @Column(name = "estimated_half_life", precision = 10, scale = 4)
  private BigDecimal estimatedHalfLife;

  /** HLR·SRS 도입 시 재검토. MVP에서는 NULL이다 (ERD 8절). */
  @Column(name = "next_review_at")
  private LocalDateTime nextReviewAt;

  /** 분석 트랜잭션이 성공한 시각. 자동 ON UPDATE 없이 명시 기록한다. */
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  /** BKT 숙련 확률 0~1. 첫 유효 관측 분석 때 계산해 저장한다. */
  @Column(name = "mastery_probability", nullable = false, precision = 7, scale = 6)
  private BigDecimal masteryProbability;

  /** 백엔드 BKT 설정 식별자 (최초 BKT_V1). 프론트 값을 쓰지 않는다. */
  @Column(name = "algorithm_version", nullable = false, length = 50)
  private String algorithmVersion;

  private UserSkillMemory(
      UserSkillMemoryId id,
      boolean correct,
      BigDecimal masteryProbability,
      String algorithmVersion,
      LocalDateTime practicedAt,
      LocalDateTime now) {
    this.id = requireNonNull(id, "사용자·요소 식별자");
    this.totalSeen = 1;
    this.totalCorrect = correct ? 1 : 0;
    this.totalIncorrect = correct ? 0 : 1;
    this.masteryProbability = requireProbability(masteryProbability);
    this.algorithmVersion = requireText(algorithmVersion, "알고리즘 버전");
    this.lastPracticedAt = practicedAt;
    this.updatedAt = requireNonNull(now, "갱신 시각");
  }

  /**
   * 첫 유효 관측을 분석할 때 행을 만든다 (ERD 5.4절).
   *
   * @param masteryProbability 서비스의 BKT 계산 결과 (0~1)
   * @param practicedAt 관측의 원본 Attempt attempted_at
   * @param now 분석 트랜잭션 성공 시각
   */
  public static UserSkillMemory firstObservation(
      Long userId,
      Long skillId,
      boolean correct,
      BigDecimal masteryProbability,
      String algorithmVersion,
      LocalDateTime practicedAt,
      LocalDateTime now) {
    return new UserSkillMemory(
        UserSkillMemoryId.of(userId, skillId),
        correct,
        masteryProbability,
        algorithmVersion,
        practicedAt,
        now);
  }

  /**
   * 요소 결과 한 건을 반영한다. total_seen을 1, 정답/오답 카운터 중 하나만 1 증가시킨다 (ERD 5.4절).
   *
   * <p>error_count만큼 오답 카운터를 늘리지 않는다는 규칙 때문에 오류 수를 인자로 받지 않는다.
   */
  public void applyObservation(
      boolean correct,
      BigDecimal newMasteryProbability,
      String algorithmVersion,
      LocalDateTime practicedAt,
      LocalDateTime now) {
    this.totalSeen++;
    if (correct) {
      this.totalCorrect++;
    } else {
      this.totalIncorrect++;
    }
    this.masteryProbability = requireProbability(newMasteryProbability);
    this.algorithmVersion = requireText(algorithmVersion, "알고리즘 버전");
    this.lastPracticedAt = practicedAt;
    this.updatedAt = requireNonNull(now, "갱신 시각");
  }

  /** 파생 숙련 구간. 컬럼으로 저장하지 않는다 (ERD 5.4절). */
  public MasteryBand masteryBand() {
    return MasteryBand.of(masteryProbability);
  }

  /** ERD 5.4절의 카운터 불변식. 테스트와 분석 트랜잭션의 사후 조건으로 쓴다. */
  public boolean hasConsistentCounters() {
    return totalSeen == totalCorrect + totalIncorrect;
  }

  private static BigDecimal requireProbability(BigDecimal value) {
    if (value == null
        || value.compareTo(BigDecimal.ZERO) < 0
        || value.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("숙련 확률은 0 이상 1 이하여야 합니다: " + value);
    }
    return value;
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
    }
    return value;
  }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
    }
    return value;
  }
}
