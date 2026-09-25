package com.itda.learning.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemAttemptRepository extends JpaRepository<ProblemAttempt, Long> {

  List<ProblemAttempt> findAllByLearningSessionIdOrderBySessionItemNoAsc(Long learningSessionId);

  /** "내 실력" 화면의 푼 문제 수 */
  long countByUserId(Long userId);

  /** 정답률의 분자. 분모는 {@link #countByUserId} */
  long countByUserIdAndCorrectTrue(Long userId);
}
