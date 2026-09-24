package com.itda.problem.repository;

import com.itda.problem.domain.UserSkillErrorCount;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSkillErrorCountRepository extends JpaRepository<UserSkillErrorCount, Long> {
  List<UserSkillErrorCount> findAllByUserSkillMemory_IdIn(List<Long> userSkillMemoryIds);
}
