package com.itda.lecture.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.service.AnswerCell;
import com.itda.lecture.service.LectureSnapshot;
import com.itda.lecture.service.LiveAnswer;
import com.itda.problem.domain.Level;
import com.itda.problem.service.LinkedProblem;
import com.itda.support.web.WebApiTest;
import java.time.LocalDateTime;
import java.util.List;
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

  private static final LinkedProblem CONSONANT =
      new LinkedProblem(26578L, "ㄱ", "초성 ㄱ", Level.CONSONANT, List.of(8));

  @Test
  void 강사_응답은_현재_문제와_연결_문제를_정답_점형과_함께_담는다() {
    JsonNode json =
        this.objectMapper.valueToTree(
            InstructorLectureResponse.from(
                LectureSnapshot.ofInstructor(lecture(), CONSONANT, List.of(CONSONANT))));

    JsonNode current = json.get("currentProblem");
    assertThat(current.get("problemId").asString()).isEqualTo("26578");
    assertThat(current.get("displayText").asString()).isEqualTo("초성 ㄱ");
    assertThat(current.get("problemType").asString()).isEqualTo("CONSONANT");
    assertThat(current.get("cellCount").asInt()).isEqualTo(1);
    assertThat(current.get("expectedPattern").get(0).asInt()).isEqualTo(8);
    assertThat(json.get("linkedProblems").size()).isEqualTo(1);
  }

  @Test
  void 학습자_현재_문제에는_정답_점형이_없고_제출했으면_결과를_담는다() {
    Participant participant = Participant.join(42L, 8L, NOW);
    LiveAnswer answer =
        new LiveAnswer(
            5L,
            42L,
            26578L,
            false,
            50,
            NOW,
            List.of(new AnswerCell(1, 8, 8), new AnswerCell(2, 0, 3)));

    JsonNode json =
        this.objectMapper.valueToTree(
            LearnerLectureResponse.from(
                LectureSnapshot.ofLearner(lecture(), participant, CONSONANT, answer)));

    JsonNode current = json.get("currentProblem");
    assertThat(current.has("expectedPattern")).isFalse();
    assertThat(current.get("problemId").asString()).isEqualTo("26578");
    assertThat(current.get("displayText").asString()).isEqualTo("초성 ㄱ");
    assertThat(current.get("cellCount").asInt()).isEqualTo(1);
    assertThat(current.get("submitted").asBoolean()).isTrue();
    JsonNode result = json.get("myResult");
    assertThat(result.get("attemptId").asString()).isEqualTo("5");
    assertThat(result.get("lectureId").asString()).isEqualTo("42");
    assertThat(result.get("isCorrect").asBoolean()).isFalse();
    assertThat(result.get("score").asInt()).isEqualTo(50);
    assertThat(result.get("attemptedAt").asString()).isEqualTo("2026-09-26T01:00:00.000Z");
    JsonNode second = result.get("cells").get(1);
    assertThat(second.get("cellIndex").asInt()).isEqualTo(2);
    assertThat(second.get("diffMask").asInt()).isEqualTo(3);
    assertThat(second.get("isCorrect").asBoolean()).isFalse();
  }

  @Test
  void 학습자가_제출하지_않았으면_submitted는_false이고_myResult는_null이다() {
    Participant participant = Participant.join(42L, 8L, NOW);

    JsonNode json =
        this.objectMapper.valueToTree(
            LearnerLectureResponse.from(
                LectureSnapshot.ofLearner(lecture(), participant, CONSONANT, null)));

    assertThat(json.get("currentProblem").get("submitted").asBoolean()).isFalse();
    assertThat(json.get("myResult").isNull()).isTrue();
  }
}
