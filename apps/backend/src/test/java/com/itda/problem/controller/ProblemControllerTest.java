package com.itda.problem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.itda.global.security.AuthenticatedUser;
import com.itda.problem.domain.Level;
import com.itda.problem.dto.RegisterProblemRequest;
import com.itda.problem.service.LinkedProblem;
import com.itda.problem.service.ProblemService;
import com.itda.problem.service.RegisteredProblem;
import com.itda.user.domain.UserRole;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;

/**
 * 동시 등록이 (lecture_id, problem_id) UNIQUE에 걸렸을 때의 복구 경로를 검증한다 (task-3 review I1·M2). 그 제약이 아닌 무결성
 * 위반은 그대로 올라가야 한다 — 다른 오류를 200으로 감추면 안 된다.
 */
class ProblemControllerTest {

  private static final AuthenticatedUser INSTRUCTOR =
      new AuthenticatedUser(7L, UserRole.INSTRUCTOR);
  private static final long LECTURE_ID = 100L;
  private static final RegisterProblemRequest REQUEST = new RegisterProblemRequest("그리고");

  private final ProblemService problemService = mock(ProblemService.class);
  private final ProblemController controller = new ProblemController(this.problemService);

  @Test
  void 연결_UNIQUE_위반이면_기존_연결을_새로_읽어_200으로_답한다() {
    given(this.problemService.registerProblem(INSTRUCTOR, LECTURE_ID, REQUEST))
        .willThrow(lectureProblemUniqueViolation());
    LinkedProblem existing = new LinkedProblem(501L, "그리고", "그리고", Level.WORD, List.of(1, 2));
    given(this.problemService.findExistingLink(INSTRUCTOR, LECTURE_ID, REQUEST))
        .willReturn(new RegisteredProblem(existing, false));

    ResponseEntity<?> response = this.controller.registerProblem(LECTURE_ID, REQUEST, INSTRUCTOR);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    // registerProblem은 처음 한 번만 불렸다 — 재시도가 아니라 별도의 읽기 전용 조회로 복구했다는 뜻이다.
    verify(this.problemService, times(1)).registerProblem(INSTRUCTOR, LECTURE_ID, REQUEST);
    verify(this.problemService).findExistingLink(INSTRUCTOR, LECTURE_ID, REQUEST);
  }

  @Test
  void 다른_무결성_위반은_다시_던진다() {
    DataIntegrityViolationException otherViolation = otherUniqueViolation();
    given(this.problemService.registerProblem(INSTRUCTOR, LECTURE_ID, REQUEST))
        .willThrow(otherViolation);

    assertThatThrownBy(() -> this.controller.registerProblem(LECTURE_ID, REQUEST, INSTRUCTOR))
        .isSameAs(otherViolation);

    verify(this.problemService, never()).findExistingLink(INSTRUCTOR, LECTURE_ID, REQUEST);
  }

  private static DataIntegrityViolationException lectureProblemUniqueViolation() {
    return constraintViolation(ConstraintKind.UNIQUE, "uk_lecture_problems_lecture_problem");
  }

  /** 종류는 같은 UNIQUE지만 이름이 다르다 — 예: problem_skills의 (problem_id, skill_id) 중복. */
  private static DataIntegrityViolationException otherUniqueViolation() {
    return constraintViolation(ConstraintKind.UNIQUE, "uk_problem_skills_problem_skill");
  }

  private static DataIntegrityViolationException constraintViolation(
      ConstraintKind kind, String constraintName) {
    return new DataIntegrityViolationException(
        "무결성 위반",
        new ConstraintViolationException(
            "제약 위반",
            new java.sql.SQLIntegrityConstraintViolationException("제약 위반", "23000", 1062),
            kind,
            constraintName));
  }
}
