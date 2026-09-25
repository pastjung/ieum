package com.itda.lecture.repository;

import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipantRepository extends JpaRepository<Participant, ParticipantId> {

  /** 수업 종료 때 left_at이 NULL인 참여자에게 종료 시각을 기록한다 (ERD 3.3절). */
  List<Participant> findAllByIdLectureIdAndLeftAtIsNull(Long lectureId);
}
