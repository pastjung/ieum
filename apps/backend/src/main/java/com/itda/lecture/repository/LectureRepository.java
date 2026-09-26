package com.itda.lecture.repository;

import com.itda.lecture.domain.Lecture;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LectureRepository extends JpaRepository<Lecture, Long> {
  Optional<Lecture> findById(Long lecturId);
}
