package com.itda.lecture.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * participants — 수업 참여 사실 (ERD 3.3절).
 *
 * <p>출석률·지각·중도이탈 판정은 하지 않는다. 복합 PK 한 행만 유지하며 재입장별 이력 행이나 체류 시간 집계를 두지 않는다.
 */
@Entity
@Table(name = "participants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Participant {

  @EmbeddedId private ParticipantId id;

  /** 최초 참여 성공 시각. 사전 입장이면 수업 started_at보다 빠를 수 있다. */
  @Column(name = "joined_at", nullable = false)
  private LocalDateTime joinedAt;

  /** 마지막 퇴장 또는 수업 종료 시각. 현재 참여 중이면 NULL이다. */
  @Column(name = "left_at")
  private LocalDateTime leftAt;

  private Participant(ParticipantId id, LocalDateTime joinedAt) {
    if (id == null) {
      throw new IllegalArgumentException("참여 식별자는 필수입니다.");
    }
    if (joinedAt == null) {
      throw new IllegalArgumentException("참여 시각은 필수입니다.");
    }
    this.id = id;
    this.joinedAt = joinedAt;
  }

  public static Participant join(Long lectureId, Long userId, LocalDateTime now) {
    return new Participant(ParticipantId.of(lectureId, userId), now);
  }

  /** 퇴장 또는 수업 종료. 종료 트랜잭션은 left_at이 NULL인 참여자에게 ended_at을 기록한다 (ERD 3.3절). */
  public void leave(LocalDateTime now) {
    this.leftAt = now;
  }

  /** 재입장. joined_at은 유지하고 left_at만 NULL로 되돌린다 (ERD 3.3절). */
  public void rejoin() {
    this.leftAt = null;
  }

  public boolean isParticipating() {
    return leftAt == null;
  }
}
