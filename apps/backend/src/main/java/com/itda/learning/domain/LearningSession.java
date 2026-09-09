package com.itda.learning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * learning_sessions — 한 학습자가 한 커리큘럼 단계에서 수행하는 한 번의 개인 학습·복습 (ERD 5.5절).
 *
 * <p>아직 풀지 않은 문항까지 포함한 확정 배정 목록을 소유한다. 중단 후 재개는 같은 행을 쓰고 새 학습은 새 행이 된다. 현재 화면·중단 시각·미제출 답안은 저장하지
 * 않는다.
 *
 * <p>이어하기 위치는 별도 커서 없이 배정 순번과 저장된 풀이의 session_item_no를 대조해 계산한다. 그 대조에는 problem_attempts 조회가 필요하므로
 * 애플리케이션 서비스가 담당한다.
 */
@Entity
@Table(name = "learning_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningSession {

  /** ERD 5.5절: 현재 정확히 10항목. 문항 수 변경 가능성을 고려해 백엔드 상수로 둔다. */
  public static final int ITEM_COUNT = 10;

  /** ERD 4.7절: 1~5번에 서로 다른 문제 5개, 6~10번에 그 5개를 한 번씩 다시 배정한다. */
  public static final int DISTINCT_PROBLEM_COUNT = 5;

  private static final int REPEAT_PER_PROBLEM = ITEM_COUNT / DISTINCT_PROBLEM_COUNT;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 세션 소유자 users.id. 본인 접근 권한은 서비스가 확인한다. */
  @Column(name = "user_id", nullable = false)
  private Long userId;

  /** 이번 학습의 기준 curriculum_stages.id. 한 세션은 단계 하나에 속한다. */
  @Column(name = "curriculum_stage_id", nullable = false)
  private Long curriculumStageId;

  /**
   * 확정된 배정 목록. item_no 오름차순으로 저장한다.
   *
   * <p>MariaDB의 JSON은 LONGTEXT COLLATE utf8mb4_bin 기반 별칭이다 (ERD 7절). JSON_VALID는 문법 검사일 뿐이므로 배정
   * 개수·순번 ·반복 규칙은 아래 검증이, 문제 존재·단계 소속은 서비스가 확인한다. DB 기본값을 두지 않는다.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "assigned_problems", nullable = false)
  private List<AssignedProblem> assignedProblems = new ArrayList<>();

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /** 모든 배정 위치에 유효 독립 풀이가 저장된 순간, 마지막 제출 트랜잭션에서 최초 한 번 기록한다. */
  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  /** BKT·취약점을 user_skill_memory에 반영한 트랜잭션에서 최초 한 번 기록한다. 실패 시 NULL을 유지한다. */
  @Column(name = "analysis_applied_at")
  private LocalDateTime analysisAppliedAt;

  private LearningSession(
      Long userId,
      Long curriculumStageId,
      List<AssignedProblem> assignedProblems,
      LocalDateTime now) {
    if (userId == null || curriculumStageId == null) {
      throw new IllegalArgumentException("사용자와 기준 단계는 모두 필수입니다.");
    }
    if (now == null) {
      throw new IllegalArgumentException("생성 시각은 필수입니다.");
    }
    this.userId = userId;
    this.curriculumStageId = curriculumStageId;
    this.assignedProblems = requireValidAssignment(assignedProblems);
    this.createdAt = now;
  }

  /**
   * 배정 목록을 확정해 세션을 만든다. 빈·부분 세션은 만들지 않는다 (ERD 4.7절).
   *
   * @param assignedProblems item_no 오름차순 배정 목록
   */
  public static LearningSession create(
      Long userId,
      Long curriculumStageId,
      List<AssignedProblem> assignedProblems,
      LocalDateTime now) {
    return new LearningSession(userId, curriculumStageId, assignedProblems, now);
  }

  /** 시각 두 개에서 상태를 파생한다 (ERD 5.5절). */
  public SessionStatus status() {
    if (completedAt == null) {
      return SessionStatus.IN_PROGRESS;
    }
    return analysisAppliedAt == null ? SessionStatus.ANALYSIS_PENDING : SessionStatus.ANALYZED;
  }

  /** 학습 완료. 최초 한 번만 기록하며 이어하기·재조회로 기존 시각을 바꾸지 않는다. */
  public void markCompleted(LocalDateTime now) {
    if (completedAt != null) {
      throw new IllegalStateException("이미 완료된 세션입니다.");
    }
    this.completedAt = requireNonNull(now, "완료 시각");
  }

  /** 분석 반영 성공. 완료된 세션에만, 최초 한 번만 기록한다. 계산만 끝낸 시점을 반영 완료로 기록하지 않는다. */
  public void markAnalysisApplied(LocalDateTime now) {
    if (completedAt == null) {
      throw new IllegalStateException("완료되지 않은 세션은 분석을 반영할 수 없습니다.");
    }
    if (analysisAppliedAt != null) {
      throw new IllegalStateException("이미 분석이 반영된 세션입니다.");
    }
    this.analysisAppliedAt = requireNonNull(now, "분석 반영 시각");
  }

  public List<AssignedProblem> getAssignedProblems() {
    return Collections.unmodifiableList(assignedProblems);
  }

  /**
   * 배정 구조를 검증한다 (ERD 5.5절 배정 JSON 검증 + 4.7절 반복 규칙).
   *
   * <p>문제 존재 여부와 기준 단계 소속은 DB 조회가 필요하므로 여기서 확인하지 않는다.
   */
  private static List<AssignedProblem> requireValidAssignment(List<AssignedProblem> items) {
    if (items == null || items.size() != ITEM_COUNT) {
      throw new IllegalArgumentException(
          "배정은 정확히 " + ITEM_COUNT + "항목이어야 합니다: " + (items == null ? "null" : items.size()));
    }

    Set<Integer> itemNumbers = new HashSet<>();
    Map<String, Integer> occurrences = new HashMap<>();
    for (int index = 0; index < items.size(); index++) {
      AssignedProblem item = items.get(index);
      if (item == null) {
        throw new IllegalArgumentException("배정 항목은 NULL일 수 없습니다.");
      }
      if (item.itemNo() != index + 1) {
        throw new IllegalArgumentException(
            "배정은 item_no 1부터 오름차순이어야 합니다: " + (index + 1) + "번째가 " + item.itemNo());
      }
      if (!itemNumbers.add(item.itemNo())) {
        throw new IllegalArgumentException("배정 순번이 중복되었습니다: " + item.itemNo());
      }
      if (index > 0 && items.get(index - 1).problemId().equals(item.problemId())) {
        throw new IllegalArgumentException("같은 문제를 연속 배정할 수 없습니다: item_no " + item.itemNo());
      }
      occurrences.merge(item.problemId(), 1, Integer::sum);
    }

    if (occurrences.size() != DISTINCT_PROBLEM_COUNT) {
      throw new IllegalArgumentException(
          "서로 다른 문제 " + DISTINCT_PROBLEM_COUNT + "개를 배정해야 합니다: " + occurrences.size());
    }
    occurrences.forEach(
        (problemId, count) -> {
          if (count != REPEAT_PER_PROBLEM) {
            throw new IllegalArgumentException(
                "각 문제는 정확히 "
                    + REPEAT_PER_PROBLEM
                    + "번 배정되어야 합니다: 문제 "
                    + problemId
                    + "가 "
                    + count
                    + "번");
          }
        });
    return new ArrayList<>(items);
  }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
    }
    return value;
  }
}
