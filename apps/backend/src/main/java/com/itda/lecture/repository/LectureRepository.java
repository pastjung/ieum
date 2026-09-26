package com.itda.lecture.repository;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LectureRepository extends JpaRepository<Lecture, Long> {
  Optional<Lecture> findById(Long lecturId);

  Optional<Lecture> findByParticipationCode(String participationCode);

  boolean existsByParticipationCode(String participationCode);

  Page<Lecture> findAllByInstructorUserId(Long instructorUserId, Pageable pageable);

  Page<Lecture> findAllByInstructorUserIdAndStatus(
      Long instructorUserId, LectureStatus status, Pageable pageable);
}
