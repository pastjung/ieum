package com.itda.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.itda.curriculum.domain.CurriculumStage;
import com.itda.curriculum.domain.CurriculumStageRepository;
import com.itda.global.FastApiClient;
import com.itda.global.security.AuthenticatedUser;
import com.itda.learning.domain.LearningSession;
import com.itda.learning.domain.LearningSessionRepository;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureProblem;
import com.itda.lecture.repository.LectureProblemRepository;
import com.itda.lecture.repository.LectureRepository;
import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.ProblemSession;
import com.itda.problem.domain.ProblemSkill;
import com.itda.problem.domain.SkillType;
import com.itda.problem.dto.NewStudyRequest;
import com.itda.problem.dto.ProblemAnalyzeResponse;
import com.itda.problem.dto.ProblemsResponse;
import com.itda.problem.dto.RegisterProblemRequest;
import com.itda.problem.dto.SkillSet;
import com.itda.problem.repository.LearningSkillQueryRepository;
import com.itda.problem.repository.LearningSkillRepository;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemRepository;
import com.itda.problem.repository.ProblemSessionRepository;
import com.itda.problem.repository.ProblemSkillRepository;
import com.itda.problem.repository.UserWrongProblemRepository;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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

  private static final AuthenticatedUser INSTRUCTOR =
      new AuthenticatedUser(INSTRUCTOR_ID, UserRole.INSTRUCTOR);

  @Mock private ProblemRepository problemRepository;
  @Mock private LectureProblemRepository lectureProblemRepository;
  @Mock private ProblemCellRepository problemCellRepository;
  @Mock private ProblemSkillRepository problemSkillRepository;
  @Mock private LectureRepository lectureRepository;
  @Mock private LearningSkillRepository learningSkillRepository;
  @Mock private LearningSkillQueryRepository learningSkillQueryRepository;
  @Mock private LearningSessionRepository learningSessionRepository;
  @Mock private CurriculumStageRepository curriculumStageRepository;
  @Mock private ProblemSessionRepository problemSessionRepository;
  @Mock private UserWrongProblemRepository userWrongProblemRepository;
  @Mock private FastApiClient fastApiClient;

  @InjectMocks private ProblemService problemService;

  @Captor private ArgumentCaptor<Problem> problemCaptor;
  @Captor private ArgumentCaptor<List<ProblemSkill>> problemSkillsCaptor;
  @Captor private ArgumentCaptor<List<ProblemCell>> problemCellsCaptor;
  @Captor private ArgumentCaptor<LectureProblem> lectureProblemCaptor;

  @Test
  void 등록한_문제는_분석이_돌려준_단계와_목표_텍스트로_저장된다() {
    givenLecture();
    givenAnalysis(Level.WORD, List.of(0b000001, 0b100000), List.of(skillSet("ㄱ")));
    givenSkills(skill("ㄱ"));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방"));

    verify(problemRepository).save(problemCaptor.capture());
    Problem saved = problemCaptor.getValue();
    assertThat(saved.getLevel()).isEqualTo(Level.WORD);
    assertThat(saved.getTargetText()).isEqualTo("가방");
  }

  @Test
  void 분석이_돌려준_점형이_1부터의_순번으로_저장된다() {
    givenLecture();
    givenAnalysis(Level.WORD, List.of(0b000001, 0b100000, 0), List.of(skillSet("ㄱ")));
    givenSkills(skill("ㄱ"));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방"));

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
    givenLecture();
    givenAnalysis(Level.WORD, List.of(0b000001), List.of(skillSet("ㄱ"), skillSet("ㅏ")));
    LearningSkill first = skill("ㄱ");
    LearningSkill second = skill("ㅏ");
    givenSkills(first, second);

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방"));

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
    Lecture lecture = givenLecture();
    givenAnalysis(Level.WORD, List.of(0b000001), List.of(skillSet("ㄱ")));
    givenSkills(skill("ㄱ"));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방"));

    verify(lectureProblemRepository).save(lectureProblemCaptor.capture());
    assertThat(lectureProblemCaptor.getValue().getLecture()).isSameAs(lecture);
    assertThat(lectureProblemCaptor.getValue().getProblem()).isNotNull();
  }

  @ParameterizedTest
  @EnumSource(
      value = UserRole.class,
      names = {"LEARNER", "ADMIN"})
  void 강사가_아니면_문제를_등록할_수_없다(UserRole role) {
    AuthenticatedUser other = new AuthenticatedUser(9L, role);

    assertThatThrownBy(
            () ->
                problemService.registerProblem(other, LECTURE_ID, new RegisterProblemRequest("가방")))
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
                    INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")))
        .isInstanceOf(RuntimeException.class);

    verify(fastApiClient, never()).analyze(any());
    verify(problemRepository, never()).save(any());
  }

  @Test
  void 본인_수업이_아니면_문제를_등록할_수_없다() {
    Lecture other = Lecture.schedule(INSTRUCTOR_ID + 1, "남의 수업", null, "123456", null, NOW);
    other.open(NOW);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(other));

    assertThatThrownBy(
            () ->
                problemService.registerProblem(
                    INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")))
        .isInstanceOf(RuntimeException.class);

    verify(fastApiClient, never()).analyze(any());
    verify(problemRepository, never()).save(any());
  }

  @Test
  void 진행_중이_아닌_수업에는_문제를_등록할_수_없다() {
    Lecture scheduled = Lecture.schedule(INSTRUCTOR_ID, "점자 기초", null, "123456", null, NOW);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(scheduled));

    assertThatThrownBy(
            () ->
                problemService.registerProblem(
                    INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")))
        .isInstanceOf(RuntimeException.class);

    verify(fastApiClient, never()).analyze(any());
    verify(problemRepository, never()).save(any());
  }

  @Test
  void 분석_결과에_문제_유형이_없으면_저장하지_않는다() {
    givenLecture();
    givenAnalysis(null, List.of(0b000001), List.of(skillSet("ㄱ")));

    assertThatThrownBy(
            () ->
                problemService.registerProblem(
                    INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")))
        .isInstanceOf(RuntimeException.class);

    verify(problemRepository, never()).save(any());
  }

  @Test
  void 분석_결과에_Cell이_없으면_저장하지_않는다() {
    givenLecture();
    givenAnalysis(Level.WORD, null, List.of(skillSet("ㄱ")));

    assertThatThrownBy(
            () ->
                problemService.registerProblem(
                    INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")))
        .isInstanceOf(RuntimeException.class);

    verify(problemRepository, never()).save(any());
  }

  /** Cell 0개 문제는 채점할 수 없다 — ERD 4.4절이 문제를 1~6 Cell로 규정한다. */
  @Test
  void 분석_결과의_Cell_목록이_비어_있으면_저장하지_않는다() {
    givenLecture();
    givenAnalysis(Level.WORD, List.of(), List.of(skillSet("ㄱ")));

    assertThatThrownBy(
            () ->
                problemService.registerProblem(
                    INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")))
        .isInstanceOf(RuntimeException.class);

    verify(problemRepository, never()).save(any());
  }

  /**
   * 요소 없는 문제는 ERD 4.6절이 금지한다. 빈 목록을 그대로 넘기면 조건 없는 조회가 되어 learning_skills 전체가 한 문제에 연결되므로, 조회 자체를 하지
   * 않는 것까지 고정한다.
   */
  @Test
  void 분석_결과의_학습_요소_목록이_비어_있으면_저장하지_않는다() {
    givenLecture();
    givenAnalysis(Level.WORD, List.of(0b000001), List.of());

    assertThatThrownBy(
            () ->
                problemService.registerProblem(
                    INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")))
        .isInstanceOf(RuntimeException.class);

    verify(problemRepository, never()).save(any());
    verify(learningSkillQueryRepository, never()).findAllBySkillSets(any());
  }

  @Test
  void 분석_결과에_학습_요소가_없으면_저장하지_않는다() {
    givenLecture();
    givenAnalysis(Level.WORD, List.of(0b000001), null);

    assertThatThrownBy(
            () ->
                problemService.registerProblem(
                    INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")))
        .isInstanceOf(RuntimeException.class);

    verify(problemRepository, never()).save(any());
    verify(learningSkillQueryRepository, never()).findAllBySkillSets(any());
  }

  @Test
  void 세션_조회_응답의_각_문항이_problemSessionId를_갖는다() {
    // 앱은 이 값으로만 완료 제출을 만들 수 있다. problemId로부터 problemSessionId를 유도할
    // 방법이 앱에는 없다 — 그 대응은 서버만 안다.
    long sessionId = 55L;
    long learnerId = 9L;
    AuthenticatedUser learner = new AuthenticatedUser(learnerId, UserRole.LEARNER);

    LearningSession session = new LearningSession(learnerId, null);
    given(learningSessionRepository.findById(sessionId)).willReturn(Optional.of(session));

    Problem problem = new Problem(Level.WORD, "가방");
    ReflectionTestUtils.setField(problem, "id", 31L);
    ProblemSession assigned = new ProblemSession(problem, session, 1L);
    ReflectionTestUtils.setField(assigned, "id", 101L);
    given(problemSessionRepository.findAllBySession_IdOrderBySequence(sessionId))
        .willReturn(List.of(assigned));

    given(problemCellRepository.findAllByProblem_IdInOrderByProblem_IdAscCellIndexAsc(List.of(31L)))
        .willReturn(List.of());

    ProblemsResponse response = problemService.studyWithOldSession(learner, sessionId);

    assertThat(response.problems())
        .singleElement()
        .satisfies(p -> assertThat(p.problemSessionId()).isEqualTo(101L));
  }

  /**
   * {@code learningWithNewSession}은 오답·일반 문제를 합쳐 {@code Collections.shuffle}한 뒤, 같은 {@code
   * randomProblems} 인덱스로 저장(saveAll)과 응답 조립 두 곳을 순회한다. 이 인덱스 대응이 어긋나면(예: 저장 루프와 응답 루프가 서로 다른 순서로
   * 읽으면) 사용자가 실제로 배정받은 문항과 다른 {@code problemSessionId}가 응답에 실린다 — 화면은 멀쩡한데 완료 제출만 엉뚱한 배정에 기록되는 조용한
   * 채점 버그다.
   *
   * <p>순서가 아니라 쌍이 맞는지를 검증한다: {@code saveAll}에 실제로 넘어간 {@link ProblemSession} 목록에서 problemId → 배정 ID
   * 매핑을 만든 뒤, 응답의 각 문항이 자기 problemId에 매핑된 배정 ID를 갖는지 확인한다. 이렇게 하면 {@code Collections.shuffle}이 만드는
   * 순서 자체는 검증 대상이 아니다.
   */
  @Test
  void 신규_세션_응답의_각_문항이_자신을_배정한_ProblemSession_ID를_갖는다() {
    long learnerId = 9L;
    long curriculumId = 1L;
    AuthenticatedUser learner = new AuthenticatedUser(learnerId, UserRole.LEARNER);

    CurriculumStage stage = mock(CurriculumStage.class);
    given(stage.getLevel()).willReturn(Level.WORD);
    given(curriculumStageRepository.findById(curriculumId)).willReturn(Optional.of(stage));

    given(
            userWrongProblemRepository.findAllByUser_IdAndLevelAndIsCompletedIsNull(
                learnerId, Level.WORD))
        .willReturn(List.of());

    List<Problem> problems = new ArrayList<>();
    for (long i = 1; i <= 12; i++) {
      Problem p = new Problem(Level.WORD, "문제" + i);
      ReflectionTestUtils.setField(p, "id", i);
      problems.add(p);
    }
    given(problemRepository.findAllByLevelAndIdNotIn(Level.WORD, List.of())).willReturn(problems);

    given(problemCellRepository.findAllByProblem_IdInOrderByProblem_IdAscCellIndexAsc(any()))
        .willReturn(List.of());

    // saveAll은 IDENTITY 채번을 흉내낸다 — 넘어온 순서대로 서로 다른 ID를 매긴다. 인덱스가 어긋나면
    // 저장된 배정과 problemId의 실제 짝이 뒤바뀌어야 이 스텁 아래에서 테스트가 실패한다.
    given(problemSessionRepository.saveAll(any()))
        .willAnswer(
            invocation -> {
              List<ProblemSession> sessions = invocation.getArgument(0);
              long nextId = 1000L;
              for (ProblemSession ps : sessions) {
                ReflectionTestUtils.setField(ps, "id", nextId++);
              }
              return sessions;
            });

    ProblemsResponse response =
        problemService.learningWithNewSession(learner, new NewStudyRequest(curriculumId));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ProblemSession>> sessionsCaptor = ArgumentCaptor.forClass(List.class);
    verify(problemSessionRepository).saveAll(sessionsCaptor.capture());
    Map<Long, Long> problemIdToAssignedSessionId =
        sessionsCaptor.getValue().stream()
            .collect(Collectors.toMap(ps -> ps.getProblem().getId(), ProblemSession::getId));

    assertThat(response.problems()).hasSize(12);
    assertThat(response.problems())
        .allSatisfy(
            detail ->
                assertThat(detail.problemSessionId())
                    .isEqualTo(problemIdToAssignedSessionId.get(detail.problemId())));
  }

  private Lecture givenLecture() {
    Lecture lecture = Lecture.schedule(INSTRUCTOR_ID, "점자 기초", null, "123456", null, NOW);
    lecture.open(NOW);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(lecture));
    return lecture;
  }

  private void givenAnalysis(Level type, List<Integer> cells, List<SkillSet> skillSets) {
    given(fastApiClient.analyze(any()))
        .willReturn(new ProblemAnalyzeResponse(type, cells, skillSets));
  }

  private void givenSkills(LearningSkill... skills) {
    given(learningSkillQueryRepository.findAllBySkillSets(any())).willReturn(List.of(skills));
  }

  private static SkillSet skillSet(String metadata) {
    return new SkillSet(SkillType.SKILL_01, metadata);
  }

  private static LearningSkill skill(String metadata) {
    return LearningSkill.of(SkillType.SKILL_01, metadata, "요소 " + metadata, null);
  }
}
