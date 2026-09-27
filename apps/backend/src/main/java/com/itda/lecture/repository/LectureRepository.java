package com.itda.lecture.repository;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LectureRepository extends JpaRepository<Lecture, Long> {
  Optional<Lecture> findById(Long lecturId);

  Optional<Lecture> findByParticipationCode(String participationCode);

  boolean existsByParticipationCode(String participationCode);

  Page<Lecture> findAllByInstructorUserId(Long instructorUserId, Pageable pageable);

  Page<Lecture> findAllByInstructorUserIdAndStatus(
      Long instructorUserId, LectureStatus status, Pageable pageable);

  /** 행을 잠그고 읽는다. 종료·출제·LIVE 답안 저장을 같은 lectures 행 잠금으로 직렬화한다 (API 명세 8.1절). 트랜잭션 안에서만 부른다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from Lecture l where l.id = :id")
  Optional<Lecture> findByIdForUpdate(@Param("id") Long id);
}
