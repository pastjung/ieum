package com.itda.lecture.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * participants의 복합 PK (lecture_id, user_id) — ERD 3.3절.
 *
 * <p>연결 테이블이므로 연관 객체 대신 두 식별자만 담는다. 같은 수업·사용자 연결 중복을 PK가 막는다.
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParticipantId implements Serializable {

  @Column(name = "lecture_id", nullable = false)
  private Long lectureId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  private ParticipantId(Long lectureId, Long userId) {
    this.lectureId = lectureId;
    this.userId = userId;
  }

  public static ParticipantId of(Long lectureId, Long userId) {
    if (lectureId == null || userId == null) {
      throw new IllegalArgumentException("수업과 사용자는 모두 필수입니다.");
    }
    return new ParticipantId(lectureId, userId);
  }
}
