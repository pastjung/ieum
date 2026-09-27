package com.itda.lecture.repository;

import com.itda.lecture.domain.LectureIdCount;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParticipantRepository extends JpaRepository<Participant, ParticipantId> {

  /** 수업 종료 때 left_at이 NULL인 참여자에게 종료 시각을 기록한다 (ERD 3.3절). */
  List<Participant> findAllByIdLectureIdAndLeftAtIsNull(Long lectureId);

  /** 수업 목록 항목의 participantCount. 페이지에 실린 수업 id들로 한 번에 묶어 센다 (API 명세 8.2절). */
  @Query(
      "select p.id.lectureId as lectureId, count(p) as count "
          + "from Participant p where p.id.lectureId in :lectureIds group by p.id.lectureId")
  List<LectureIdCount> countByLectureIdIn(@Param("lectureIds") List<Long> lectureIds);

  /** 수업 목록 항목의 joinedCount(left_at이 NULL인 현재 참여자). {@link #countByLectureIdIn}과 같은 방식으로 묶어 센다. */
  @Query(
      "select p.id.lectureId as lectureId, count(p) as count "
          + "from Participant p where p.id.lectureId in :lectureIds and p.leftAt is null "
          + "group by p.id.lectureId")
  List<LectureIdCount> countActiveByLectureIdIn(@Param("lectureIds") List<Long> lectureIds);
}
