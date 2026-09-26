package com.itda.problem.repository;

import com.itda.learning.domain.UserSkillMemory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSkillMemoryRepository extends JpaRepository<UserSkillMemory, Long> {
  List<UserSkillMemory> findAllByUser_Id(long l);
}
