package com.itda.lecture.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.service.LectureSnapshot;
import com.itda.support.web.WebApiTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 강사·학습자 응답의 필드 분리와 ID 문자열화를 확인한다.
 *
 * <p>직렬화 설정은 애플리케이션의 {@code ObjectMapper}가 결정하므로 그 bean을 그대로 주입받는다 ({@link
 * com.itda.learning.dto.LearningSessionCompletionResponseJsonTest} 참고).
 */
@WebApiTest
class LectureResponseJsonTest {

  @Autowired private ObjectMapper objectMapper;

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 1, 0);

  private Lecture lecture() {
    Lecture lecture = Lecture.schedule(7L, "약자 4회차", null, "012345", null, NOW);
    ReflectionTestUtils.setField(lecture, "id", 42L);
    return lecture;
  }

  @Test
  void 강사_응답은_참여_코드를_포함하고_학습자_필드가_없다() {
    JsonNode json =
        this.objectMapper.valueToTree(
            InstructorLectureResponse.from(LectureSnapshot.ofInstructor(lecture())));

    assertThat(json.get("lectureId").asString()).isEqualTo("42");
    assertThat(json.get("participationCode").asString()).isEqualTo("012345");
    assertThat(json.get("status").asString()).isEqualTo("SCHEDULED");
    assertThat(json.get("createdAt").asString()).isEqualTo("2026-09-26T01:00:00.000Z");
    assertThat(json.has("currentProblem")).isTrue();
    assertThat(json.has("myParticipation")).isFalse();
  }

  @Test
  void 학습자_응답은_참여_코드를_숨긴다() {
    Participant participant = Participant.join(42L, 8L, NOW);
    JsonNode json =
        this.objectMapper.valueToTree(
            LearnerLectureResponse.from(LectureSnapshot.ofLearner(lecture(), participant)));

    assertThat(json.has("participationCode")).isFalse();
    assertThat(json.get("myParticipation").get("joinedAt").asString())
        .isEqualTo("2026-09-26T01:00:00.000Z");
    assertThat(json.get("reviewableCurriculumStages").isArray()).isTrue();
  }
}
