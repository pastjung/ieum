package com.itda.lecture.repository;

import com.itda.lecture.domain.LectureProblem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LectureProblemRepository extends JpaRepository<LectureProblem, Long> {}
