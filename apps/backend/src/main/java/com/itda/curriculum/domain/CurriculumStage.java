package com.itda.curriculum.domain;

import com.itda.problem.domain.ProblemType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "curriculum_stages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CurriculumStage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 100)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ProblemType level;

  @Column(name = "order_no", nullable = false, unique = true)
  private int orderNo;

  @Column(columnDefinition = "TEXT")
  private String description;
}
