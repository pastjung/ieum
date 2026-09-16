package com.itda.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureProblem;
import com.itda.lecture.repository.LectureProblemRepository;
import com.itda.lecture.repository.LectureRepository;
import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.ProblemSkill;
import com.itda.problem.domain.ProblemSourceType;
import com.itda.problem.domain.ProblemType;
import com.itda.problem.domain.SkillType;
import com.itda.problem.dto.ProblemAnalyzeResponse;
import com.itda.problem.dto.RegisterProblemRequest;
import com.itda.problem.dto.SkillSet;
import com.itda.problem.repository.LearningSkillQueryRepository;
import com.itda.problem.repository.LearningSkillRepository;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemRepository;
import com.itda.problem.repository.ProblemSkillRepository;
import com.itda.user.domain.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 강사 문제 등록의 협력 흐름을 검증한다. DB·FastAPI 없이 순수 단위 테스트로 돌린다.
 *
 * <p>ERD 4.3절이 problems·problem_cells·problem_skills를 한 트랜잭션으로 저장하도록 규정하므로, 각 저장소에 무엇이 넘어가는지를 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ProblemServiceTest {

  private static final long INSTRUCTOR_ID = 7L;
  private static final long LECTURE_ID = 100L;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 12, 0);

  @Mock private UserRepository userRepository;
  @Mock private ProblemRepository problemRepository;
  @Mock private LectureProblemRepository lectureProblemRepository;
  @Mock private ProblemCellRepository problemCellRepository;
  @Mock private ProblemSkillRepository problemSkillRepository;
  @Mock private LectureRepository lectureRepository;
  @Mock private LearningSkillRepository learningSkillRepository;
  @Mock private LearningSkillQueryRepository learningSkillQueryRepository;
  @Mock private FastApiClient fastApiClient;

  @InjectMocks private ProblemService problemService;

  @Captor private ArgumentCaptor<Problem> problemCaptor;
  @Captor private ArgumentCaptor<List<ProblemSkill>> problemSkillsCaptor;
  @Captor private ArgumentCaptor<List<ProblemCell>> problemCellsCaptor;
  @Captor private ArgumentCaptor<LectureProblem> lectureProblemCaptor;

  @Test
  void 등록한_문제는_강사_소유의_INSTRUCTOR_문제로_저장된다() {
    givenOpenLecture();
    givenAnalysis(ProblemType.WORD, List.of(0b000001, 0b100000), List.of(skillSet("ㄱ")));
    given(learningSkillQueryRepository.findAllBySkillSets(any())).willReturn(List.of(skill("ㄱ")));

    problemService.registerProblem(INSTRUCTOR_ID, LECTURE_ID, new RegisterProblemRequest("가방"));

    verify(problemRepository).save(problemCaptor.capture());
    Problem saved = problemCaptor.getValue();
    assertThat(saved.getCreatorUserId()).isEqualTo(INSTRUCTOR_ID);
    assertThat(saved.getSourceType()).isEqualTo(ProblemSourceType.INSTRUCTOR);
    assertThat(saved.getProblemType()).isEqualTo(ProblemType.WORD);
    assertThat(saved.getTargetText()).isEqualTo("가방");
  }

  @Test
  void 분석이_돌려준_점형이_1부터의_순번으로_저장된다() {
    givenOpenLecture();
    givenAnalysis(ProblemType.WORD, List.of(0b000001, 0b100000, 0), List.of(skillSet("ㄱ")));
    given(learningSkillQueryRepository.findAllBySkillSets(any())).willReturn(List.of(skill("ㄱ")));

    problemService.registerProblem(INSTRUCTOR_ID, LECTURE_ID, new RegisterProblemRequest("가방"));

    verify(problemCellRepository).saveAll(problemCellsCaptor.capture());
    List<ProblemCell> cells = problemCellsCaptor.getValue();
    assertThat(cells).hasSize(3);
    assertThat(cells).extracting(ProblemCell::getCellIndex).containsExactly(1, 2, 3);
    assertThat(cells)
        .extracting(ProblemCell::getExpectedPattern)
        .containsExactly(0b000001, 0b100000, 0);
  }

  @Test
  void 조회한_학습_요소마다_문제_요소_연결을_만든다() {
    givenOpenLecture();
    givenAnalysis(
        ProblemType.WORD, List.of(0b000001), List.of(skillSet("ㄱ"), skillSet("ㅏ")));
    LearningSkill first = skill("ㄱ");
    LearningSkill second = skill("ㅏ");
    given(learningSkillQueryRepository.findAllBySkillSets(any()))
        .willReturn(List.of(first, second));

    problemService.registerProblem(INSTRUCTOR_ID, LECTURE_ID, new RegisterProblemRequest("가방"));

    verify(problemRepository).save(problemCaptor.capture());
    verify(problemSkillRepository).saveAll(problemSkillsCaptor.capture());
    Problem saved = problemCaptor.getValue();
    List<ProblemSkill> links = problemSkillsCaptor.getValue();
    assertThat(links).hasSize(2);
    assertThat(links).extracting(ProblemSkill::getSkill).containsExactly(first, second);
    assertThat(links).allSatisfy(link -> assertThat(link.getProblem()).isSameAs(saved));
  }

  @Test
  void 등록한_문제를_해당_수업에_연결한다() {
    Lecture lecture = givenOpenLecture();
    givenAnalysis(ProblemType.WORD, List.of(0b000001), List.of(skillSet("ㄱ")));
    given(learningSkillQueryRepository.findAllBySkillSets(any())).willReturn(List.of(skill("ㄱ")));

    problemService.registerProblem(INSTRUCTOR_ID, LECTURE_ID, new RegisterProblemRequest("가방"));

    verify(lectureProblemRepository).save(lectureProblemCaptor.capture());
    assertThat(lectureProblemCaptor.getValue().getLecture()).isSameAs(lecture);
    assertThat(lectureProblemCaptor.getValue().getProblem()).isNotNull();
  }

  @Test
  void 해당_수업의_강사가_아니면_거부한다() {
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(openLecture()));

    assertThatThrownBy(
            () ->
                problemService.registerProblem(
                    INSTRUCTOR_ID + 1, LECTURE_ID, new RegisterProblemRequest("가방")))
        .isInstanceOf(RuntimeException.class);

    verify(fastApiClient, never()).analyze(any());
    verify(problemRepository, never()).save(any());
  }

  @Test
  void 진행_중이_아닌_수업에는_문제를_등록할_수_없다() {
    Lecture scheduled =
        Lecture.schedule(INSTRUCTOR_ID, "점자 기초", null, "123456", null, NOW);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(scheduled));

    assertThatThrownBy(
            () ->
                problemService.registerProblem(
                    INSTRUCTOR_ID, LECTURE_ID, new RegisterProblemRequest("가방")))
        .isInstanceOf(RuntimeException.class);

    verify(fastApiClient, never()).analyze(any());
    verify(problemRepository, never()).save(any());
  }

  @Test
  void 없는_수업이면_거부한다() {
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                problemService.registerProblem(
                    INSTRUCTOR_ID, LECTURE_ID, new RegisterProblemRequest("가방")))
        .isInstanceOf(RuntimeException.class);

    verify(fastApiClient, never()).analyze(any());
  }

  private Lecture givenOpenLecture() {
    Lecture lecture = openLecture();
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(lecture));
    return lecture;
  }

  private static Lecture openLecture() {
    Lecture lecture = Lecture.schedule(INSTRUCTOR_ID, "점자 기초", null, "123456", null, NOW);
    lecture.open(NOW);
    return lecture;
  }

  private void givenAnalysis(ProblemType type, List<Integer> cells, List<SkillSet> skillSets) {
    given(fastApiClient.analyze(any()))
        .willReturn(new ProblemAnalyzeResponse(type, cells, skillSets));
  }

  private static SkillSet skillSet(String metadata) {
    return new SkillSet(SkillType.SKILL_01, metadata);
  }

  private static LearningSkill skill(String metadata) {
    return LearningSkill.of(SkillType.SKILL_01, metadata, "요소 " + metadata, null);
  }
}
