package com.itda.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.itda.curriculum.domain.CurriculumStage;
import com.itda.curriculum.domain.CurriculumStageRepository;
import com.itda.global.FastApiClient;
import com.itda.global.error.BusinessException;
import com.itda.global.error.CandidatesBusinessException;
import com.itda.global.error.CandidatesBusinessException.Candidate;
import com.itda.global.error.ErrorCode;
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
import com.itda.problem.domain.UserWrongProblem;
import com.itda.problem.dto.NewStudyRequest;
import com.itda.problem.dto.ProblemAnalyzeResponse;
import com.itda.problem.dto.ProblemsResponse;
import com.itda.problem.dto.RegisterProblemRequest;
import com.itda.problem.dto.ReviewAnalyzeResponse;
import com.itda.problem.dto.SkillSet;
import com.itda.problem.repository.LearningSkillQueryRepository;
import com.itda.problem.repository.LearningSkillRepository;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemRepository;
import com.itda.problem.repository.ProblemSessionRepository;
import com.itda.problem.repository.ProblemSkillRepository;
import com.itda.problem.repository.UserSkillErrorCountRepository;
import com.itda.problem.repository.UserSkillMemoryRepository;
import com.itda.problem.repository.UserWrongProblemRepository;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.UserJpaRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
  @Mock private UserSkillMemoryRepository userSkillMemoryRepository;
  @Mock private UserSkillErrorCountRepository userSkillErrorCountRepository;
  @Mock private FastApiClient fastApiClient;
  @Mock private UserJpaRepository userJpaRepository;
  @Mock private LinkedProblemReader linkedProblemReader;

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

    assertError(
        () -> problemService.registerProblem(other, LECTURE_ID, new RegisterProblemRequest("가방")),
        ErrorCode.INSTRUCTOR_ACCESS_REQUIRED);

    verify(fastApiClient, never()).analyze(any());
    verify(problemRepository, never()).save(any());
  }

  @Test
  void 없는_수업이면_거부한다() {
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.empty());

    assertError(
        () ->
            problemService.registerProblem(
                INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")),
        ErrorCode.LECTURE_NOT_FOUND);

    verify(fastApiClient, never()).analyze(any());
    verify(problemRepository, never()).save(any());
  }

  @Test
  void 본인_수업이_아니면_문제를_등록할_수_없다() {
    Lecture other = Lecture.schedule(INSTRUCTOR_ID + 1, "남의 수업", null, "123456", null, NOW);
    other.open(NOW);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(other));

    assertError(
        () ->
            problemService.registerProblem(
                INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")),
        ErrorCode.LECTURE_NOT_FOUND);

    verify(fastApiClient, never()).analyze(any());
    verify(problemRepository, never()).save(any());
  }

  @Test
  void 종료된_수업에는_문제를_연결할_수_없다() {
    Lecture closed = Lecture.schedule(INSTRUCTOR_ID, "점자 기초", null, "123456", null, NOW);
    closed.open(NOW);
    closed.close(NOW);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(closed));

    assertError(
        () ->
            problemService.registerProblem(
                INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")),
        ErrorCode.INVALID_LECTURE_STATE);

    verify(fastApiClient, never()).analyze(any());
    verify(problemRepository, never()).save(any());
  }

  @Test
  void 시작_전_수업에도_문제를_연결한다() {
    Lecture scheduled = Lecture.schedule(INSTRUCTOR_ID, "점자 기초", null, "123456", null, NOW);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(scheduled));
    Problem word = problem(Level.WORD, "그리고", 501L);
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("그리고", BARE_LEVELS))
        .willReturn(List.of(word));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("그리고"));

    verify(lectureProblemRepository).save(lectureProblemCaptor.capture());
    assertThat(lectureProblemCaptor.getValue().getProblem()).isSameAs(word);
  }

  // ---------- 셀 상한 (API 명세 8.2.1절, 사용자 결정) ----------

  @Test
  void 기존_문제의_셀_수가_12를_넘으면_연결하지_않는다() {
    givenLecture();
    Problem tooLong = problem(Level.WORD, "그리고", 501L);
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("그리고", BARE_LEVELS))
        .willReturn(List.of(tooLong));
    given(problemCellRepository.countByProblem_Id(501L)).willReturn(13L);

    assertError(
        () ->
            problemService.registerProblem(
                INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("그리고")),
        ErrorCode.PROBLEM_TOO_LONG);

    verify(lectureProblemRepository, never()).save(any());
  }

  @Test
  void AI가_13칸을_돌려주면_문제를_만들지_않는다() {
    givenLecture();
    givenAnalysis(
        Level.WORD, List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13), List.of(skillSet("ㄱ")));

    assertError(
        () ->
            problemService.registerProblem(
                INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")),
        ErrorCode.PROBLEM_TOO_LONG);

    verify(problemRepository, never()).save(any());
    verify(lectureProblemRepository, never()).save(any());
  }

  @Test
  void AI가_12칸을_돌려주면_연결한다() {
    givenLecture();
    givenAnalysis(
        Level.WORD, List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), List.of(skillSet("ㄱ")));
    givenSkills(skill("ㄱ"));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방"));

    verify(problemRepository).save(any());
    verify(lectureProblemRepository).save(any());
  }

  // ---------- 입력 해석 (API 명세 8.2.1절) ----------

  @Test
  void 초성_구분어는_첫소리_요소에_연결된_자음을_고른다() {
    givenLecture();
    Problem onset = problem(Level.CONSONANT, "ㄱ", 26578L);
    given(
            problemRepository.findAllByTargetTextAndLevelAndSkillType(
                "ㄱ", Level.CONSONANT, SkillType.SKILL_01))
        .willReturn(List.of(onset));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("초성 ㄱ"));

    assertThat(savedLinkProblem()).isSameAs(onset);
    verify(fastApiClient, never()).analyze(any());
  }

  @Test
  void 받침_구분어는_받침_요소에_연결된_자음을_고른다() {
    givenLecture();
    Problem coda = problem(Level.CONSONANT, "ㄱ", 26600L);
    given(
            problemRepository.findAllByTargetTextAndLevelAndSkillType(
                "ㄱ", Level.CONSONANT, SkillType.SKILL_03))
        .willReturn(List.of(coda));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("받침 ㄱ"));

    assertThat(savedLinkProblem()).isSameAs(coda);
  }

  @Test
  void 모음_구분어는_모음_문제를_고른다() {
    givenLecture();
    Problem vowel = problem(Level.VOWEL, "ㅏ", 26591L);
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("ㅏ", List.of(Level.VOWEL)))
        .willReturn(List.of(vowel));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("모음 ㅏ"));

    assertThat(savedLinkProblem()).isSameAs(vowel);
  }

  @Test
  void 음절_구분어는_약자_문제를_고른다() {
    givenLecture();
    Problem contraction = problem(Level.CONTRACTION, "가", 26620L);
    given(
            problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc(
                "가", List.of(Level.CONTRACTION)))
        .willReturn(List.of(contraction));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("음절 가"));

    assertThat(savedLinkProblem()).isSameAs(contraction);
  }

  @Test
  void 구분어가_붙은_입력에_문제가_없으면_404이고_AI로_만들지_않는다() {
    givenLecture();

    assertError(
        () ->
            problemService.registerProblem(
                INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("받침 ㅃ")),
        ErrorCode.PROBLEM_NOT_FOUND);

    verify(fastApiClient, never()).analyze(any());
    verify(lectureProblemRepository, never()).save(any());
  }

  @Test
  void 구분어_없는_낱자에_후보가_둘이면_후보를_돌려주고_연결하지_않는다() {
    givenLecture();
    List<Problem> candidates =
        List.of(problem(Level.CONSONANT, "ㄱ", 1L), problem(Level.CONSONANT, "ㄱ", 2L));
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("ㄱ", JAMO_LEVELS))
        .willReturn(candidates);
    given(linkedProblemReader.readAll(candidates))
        .willReturn(List.of(linked(1L, "초성 ㄱ"), linked(2L, "받침 ㄱ")));

    assertThatThrownBy(
            () ->
                problemService.registerProblem(
                    INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("ㄱ")))
        .isInstanceOfSatisfying(
            CandidatesBusinessException.class,
            e -> {
              assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROBLEM_TEXT_AMBIGUOUS);
              assertThat(e.getCandidates())
                  .containsExactly(new Candidate("초성 ㄱ", "초성 ㄱ"), new Candidate("받침 ㄱ", "받침 ㄱ"));
            });
    verify(lectureProblemRepository, never()).save(any());
  }

  /**
   * 자음이 SKILL_01·SKILL_03 어디에도 안 걸려 있으면 ProblemDisplay가 표시 문구로 입력 글자를 그대로 낸다. 그 후보를 그대로 후보 목록에 실으면
   * 강사가 그 문구를 다시 보내 같은 409를 반복하게 된다 — 후보에서 뺀다.
   */
  @Test
  void 구분어를_못_붙이는_후보는_제외하고_남은_하나를_연결한다() {
    givenLecture();
    Problem unprefixable = problem(Level.CONSONANT, "ㄱ", 1L);
    Problem onset = problem(Level.CONSONANT, "ㄱ", 2L);
    List<Problem> candidates = List.of(unprefixable, onset);
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("ㄱ", JAMO_LEVELS))
        .willReturn(candidates);
    given(linkedProblemReader.readAll(candidates))
        .willReturn(List.of(linked(1L, "ㄱ"), linked(2L, "초성 ㄱ")));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("ㄱ"));

    assertThat(savedLinkProblem()).isSameAs(onset);
  }

  @Test
  void 후보가_모두_구분어를_못_붙이면_404다() {
    givenLecture();
    List<Problem> candidates =
        List.of(problem(Level.CONSONANT, "ㄱ", 1L), problem(Level.CONSONANT, "ㄱ", 2L));
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("ㄱ", JAMO_LEVELS))
        .willReturn(candidates);
    given(linkedProblemReader.readAll(candidates))
        .willReturn(List.of(linked(1L, "ㄱ"), linked(2L, "ㄱ")));

    assertError(
        () ->
            problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("ㄱ")),
        ErrorCode.PROBLEM_NOT_FOUND);

    verify(lectureProblemRepository, never()).save(any());
  }

  @Test
  void 구분어_없는_낱자에_후보가_하나면_바로_연결한다() {
    givenLecture();
    Problem vowel = problem(Level.VOWEL, "ㅏ", 26591L);
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("ㅏ", JAMO_LEVELS))
        .willReturn(List.of(vowel));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("ㅏ"));

    assertThat(savedLinkProblem()).isSameAs(vowel);
  }

  @Test
  void 표시_문구가_같은_중복_후보는_하나로_보고_id가_작은_것을_연결한다() {
    givenLecture();
    Problem first = problem(Level.VOWEL, "ㅏ", 10L);
    List<Problem> candidates = List.of(first, problem(Level.VOWEL, "ㅏ", 11L));
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("ㅏ", JAMO_LEVELS))
        .willReturn(candidates);
    given(linkedProblemReader.readAll(candidates))
        .willReturn(List.of(linked(10L, "모음 ㅏ"), linked(11L, "모음 ㅏ")));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("ㅏ"));

    assertThat(savedLinkProblem()).isSameAs(first);
  }

  @Test
  void 구분어_없는_낱자에_후보가_없으면_404다() {
    givenLecture();

    assertError(
        () ->
            problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("ㄳ")),
        ErrorCode.PROBLEM_NOT_FOUND);

    verify(fastApiClient, never()).analyze(any());
  }

  @Test
  void 구분어_없는_글자는_낱말_단계를_먼저_고른다() {
    givenLecture();
    Problem word = problem(Level.WORD, "가", 20L);
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("가", BARE_LEVELS))
        .willReturn(List.of(word));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가"));

    assertThat(savedLinkProblem()).isSameAs(word);
    verify(problemRepository, never()).findAllByTargetTextOrderByIdAsc(any());
    verify(fastApiClient, never()).analyze(any());
  }

  @Test
  void 낱말_단계에_없으면_다른_단계의_같은_글자를_고른다() {
    givenLecture();
    Problem contraction = problem(Level.CONTRACTION, "억", 30L);
    given(problemRepository.findAllByTargetTextOrderByIdAsc("억")).willReturn(List.of(contraction));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("억"));

    assertThat(savedLinkProblem()).isSameAs(contraction);
    verify(fastApiClient, never()).analyze(any());
  }

  @Test
  void 앞뒤_공백을_지운_글자로_AI에_묻고_저장한다() {
    givenLecture();
    givenAnalysis(Level.WORD, List.of(0b000001), List.of(skillSet("ㄱ")));
    givenSkills(skill("ㄱ"));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("  가방  "));

    ArgumentCaptor<RegisterProblemRequest> asked =
        ArgumentCaptor.forClass(RegisterProblemRequest.class);
    verify(fastApiClient).analyze(asked.capture());
    assertThat(asked.getValue().targetText()).isEqualTo("가방");
    verify(problemRepository).save(problemCaptor.capture());
    assertThat(problemCaptor.getValue().getTargetText()).isEqualTo("가방");
  }

  // ---------- 중복 연결 ----------

  @Test
  void 이미_연결된_문제면_저장하지_않고_기존_연결로_답한다() {
    Lecture lecture = givenLecture();
    Problem word = problem(Level.WORD, "그리고", 501L);
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("그리고", BARE_LEVELS))
        .willReturn(List.of(word));
    given(lectureProblemRepository.findByLecture_IdAndProblem_Id(any(), eq(501L)))
        .willReturn(Optional.of(new LectureProblem(lecture, word)));

    RegisteredProblem result =
        problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("그리고"));

    assertThat(result.created()).isFalse();
    verify(lectureProblemRepository, never()).save(any());
  }

  @Test
  void 새로_연결하면_created이고_읽은_문제_정보를_돌려준다() {
    givenLecture();
    Problem word = problem(Level.WORD, "그리고", 501L);
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("그리고", BARE_LEVELS))
        .willReturn(List.of(word));
    LinkedProblem read = new LinkedProblem(501L, "그리고", "그리고", Level.WORD, List.of(1, 2));
    given(linkedProblemReader.read(word)).willReturn(read);

    RegisteredProblem result =
        problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("그리고"));

    assertThat(result.created()).isTrue();
    assertThat(result.problem()).isSameAs(read);
  }

  @Test
  void 분석_결과에_문제_유형이_없으면_저장하지_않는다() {
    givenLecture();
    givenAnalysis(null, List.of(0b000001), List.of(skillSet("ㄱ")));

    assertError(
        () ->
            problemService.registerProblem(
                INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")),
        ErrorCode.PROBLEM_VALIDATION_UNAVAILABLE);

    verify(problemRepository, never()).save(any());
  }

  @Test
  void 분석_결과에_Cell이_없으면_저장하지_않는다() {
    givenLecture();
    givenAnalysis(Level.WORD, null, List.of(skillSet("ㄱ")));

    assertError(
        () ->
            problemService.registerProblem(
                INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")),
        ErrorCode.PROBLEM_VALIDATION_UNAVAILABLE);

    verify(problemRepository, never()).save(any());
  }

  /** Cell 0개 문제는 채점할 수 없다 — ERD 4.4절이 문제를 1~6 Cell로 규정한다. */
  @Test
  void 분석_결과의_Cell_목록이_비어_있으면_저장하지_않는다() {
    givenLecture();
    givenAnalysis(Level.WORD, List.of(), List.of(skillSet("ㄱ")));

    assertError(
        () ->
            problemService.registerProblem(
                INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")),
        ErrorCode.PROBLEM_VALIDATION_UNAVAILABLE);

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

    assertError(
        () ->
            problemService.registerProblem(
                INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")),
        ErrorCode.PROBLEM_VALIDATION_UNAVAILABLE);

    verify(problemRepository, never()).save(any());
    verify(learningSkillQueryRepository, never()).findAllBySkillSets(any());
  }

  @Test
  void 분석_결과에_학습_요소가_없으면_저장하지_않는다() {
    givenLecture();
    givenAnalysis(Level.WORD, List.of(0b000001), null);

    assertError(
        () ->
            problemService.registerProblem(
                INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")),
        ErrorCode.PROBLEM_VALIDATION_UNAVAILABLE);

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

    User owner = mock(User.class);
    given(owner.getId()).willReturn(learnerId);
    LearningSession session = new LearningSession(owner, null);
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

    // createSession 이 소유자를 조회하고, 같은 갈래의 진행 중 세션을 먼저 닫는다 (dev의 활성 세션 관리)
    User learnerEntity = mock(User.class);
    given(userJpaRepository.findById(learnerId)).willReturn(Optional.of(learnerEntity));
    given(
            learningSessionRepository.findAllByUserAndCurriculumStageIsNotNullAndCompletedAtIsNull(
                learnerEntity))
        .willReturn(List.of());

    given(
            userWrongProblemRepository.findAllByUser_IdAndLevelAndIsCompletedFalse(
                learnerId, Level.WORD))
        .willReturn(List.of());

    List<Problem> problems = new ArrayList<>();
    for (long i = 1; i <= 12; i++) {
      Problem p = new Problem(Level.WORD, "문제" + i);
      ReflectionTestUtils.setField(p, "id", i);
      problems.add(p);
    }
    given(problemRepository.findAssignableByLevel(Level.WORD, List.of(), 12L)).willReturn(problems);

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

  @Test
  void 없는_학습_세션은_조회할_수_없다() {
    given(learningSessionRepository.findById(10L)).willReturn(Optional.empty());

    assertError(
        () -> problemService.studyWithOldSession(INSTRUCTOR, 10L),
        ErrorCode.LEARNING_SESSION_NOT_FOUND);
  }

  @Test
  void 다른_사용자의_학습_세션은_조회할_수_없다() {
    User owner = org.mockito.Mockito.mock(User.class);
    given(owner.getId()).willReturn(INSTRUCTOR_ID + 1);
    LearningSession session = new LearningSession(owner, null);
    given(learningSessionRepository.findById(10L)).willReturn(Optional.of(session));

    assertError(
        () -> problemService.studyWithOldSession(INSTRUCTOR, 10L),
        ErrorCode.LEARNING_SESSION_NOT_FOUND);
  }

  @Test
  void 종료된_학습_세션은_조회할_수_없다() {
    User owner = org.mockito.Mockito.mock(User.class);
    given(owner.getId()).willReturn(INSTRUCTOR_ID);
    LearningSession session = new LearningSession(owner, null);
    session.complete(NOW);
    given(learningSessionRepository.findById(10L)).willReturn(Optional.of(session));

    assertError(
        () -> problemService.studyWithOldSession(INSTRUCTOR, 10L),
        ErrorCode.LEARNING_SESSION_ALREADY_COMPLETED);
  }

  @Test
  void 없는_커리큘럼으로_학습_세션을_생성할_수_없다() {
    given(curriculumStageRepository.findById(5L)).willReturn(Optional.empty());

    assertError(
        () -> problemService.learningWithNewSession(INSTRUCTOR, new NewStudyRequest(5L)),
        ErrorCode.INVALID_LEARNING_SESSION_INPUT);
  }

  @Test
  void 사용자가_없으면_학습_세션을_생성할_수_없다() {
    com.itda.curriculum.domain.CurriculumStage curriculumStage =
        org.mockito.Mockito.mock(com.itda.curriculum.domain.CurriculumStage.class);
    given(curriculumStageRepository.findById(5L)).willReturn(Optional.of(curriculumStage));
    given(userJpaRepository.findById(INSTRUCTOR_ID)).willReturn(Optional.empty());

    assertError(
        () -> problemService.learningWithNewSession(INSTRUCTOR, new NewStudyRequest(5L)),
        ErrorCode.USER_NOT_FOUND);
  }

  /**
   * 오답 재출제 경로는 {@code findAssignableByLevel}의 후보에서 <b>제외</b>될 뿐 따로 배정되므로, 그 조회의 셀 상한을 거치지 않는다. 13칸
   * 이상 문제를 틀린 적 있는 학습자가 그것을 다시 받으면 FastAPI가 분석을 거부해 그 문항의 {@code ProblemSession}이 완료되지 않고, 세션 전체를
   * 끝낼 수 없다.
   */
  @Test
  void 셀이_상한을_넘는_오답_문제는_다시_출제되지_않는다() {
    long learnerId = 9L;
    long curriculumId = 1L;
    AuthenticatedUser learner = new AuthenticatedUser(learnerId, UserRole.LEARNER);

    CurriculumStage stage = mock(CurriculumStage.class);
    given(stage.getLevel()).willReturn(Level.WORD);
    given(curriculumStageRepository.findById(curriculumId)).willReturn(Optional.of(stage));

    User learnerEntity = mock(User.class);
    given(userJpaRepository.findById(learnerId)).willReturn(Optional.of(learnerEntity));
    given(
            learningSessionRepository.findAllByUserAndCurriculumStageIsNotNullAndCompletedAtIsNull(
                learnerEntity))
        .willReturn(List.of());

    Problem shortWrong = problem(101L, "짧은오답");
    Problem longWrong = problem(102L, "긴오답");
    given(
            userWrongProblemRepository.findAllByUser_IdAndLevelAndIsCompletedFalse(
                learnerId, Level.WORD))
        .willReturn(
            new ArrayList<>(
                List.of(
                    wrongProblem(learnerEntity, shortWrong),
                    wrongProblem(learnerEntity, longWrong))));

    // 셀 상한 조회는 짧은 오답만 돌려준다 — 긴 오답은 배정 후보가 아니다
    given(problemRepository.findAssignableIdsIn(List.of(101L, 102L), 12L))
        .willReturn(List.of(101L));

    List<Problem> pool = new ArrayList<>();
    for (long i = 1; i <= 11; i++) {
      pool.add(problem(i, "문제" + i));
    }
    given(problemRepository.findAssignableByLevel(Level.WORD, List.of(101L), 12L)).willReturn(pool);
    given(problemCellRepository.findAllByProblem_IdInOrderByProblem_IdAscCellIndexAsc(any()))
        .willReturn(List.of());
    given(problemSessionRepository.saveAll(any()))
        .willAnswer(invocation -> invocation.getArgument(0));

    ProblemsResponse response =
        problemService.learningWithNewSession(learner, new NewStudyRequest(curriculumId));

    assertThat(response.problems()).extracting(detail -> detail.problemId()).contains(101L);
    assertThat(response.problems()).extracting(detail -> detail.problemId()).doesNotContain(102L);
  }

  @Test
  void 출제할_문제가_부족하면_학습_세션을_생성할_수_없다() {
    com.itda.curriculum.domain.CurriculumStage curriculumStage =
        org.mockito.Mockito.mock(com.itda.curriculum.domain.CurriculumStage.class);
    given(curriculumStage.getLevel()).willReturn(Level.WORD);
    given(curriculumStageRepository.findById(5L)).willReturn(Optional.of(curriculumStage));
    User user = org.mockito.Mockito.mock(User.class);
    given(userJpaRepository.findById(INSTRUCTOR_ID)).willReturn(Optional.of(user));

    assertError(
        () -> problemService.learningWithNewSession(INSTRUCTOR, new NewStudyRequest(5L)),
        ErrorCode.LEARNING_CONTENT_NOT_READY);
  }

  /**
   * 복습 출제는 {@code HashSet}이 {@code i * 4}가 될 때까지 무작위 추출을 반복했다. 후보가 4개 미만이면 그 반복은 절대 끝나지 않는다 — 예외도
   * 나지 않고 요청 스레드가 CPU를 태우며 영구히 붙잡힌다. {@code @Timeout}을 두는 이유가 이것이다: 회귀하면 이 테스트는 실패가 아니라 <b>영영 끝나지
   * 않는다</b>.
   */
  @Test
  @Timeout(10)
  void 복습_출제_후보가_모자라면_영원히_반복하지_않고_거부한다() {
    AuthenticatedUser learner = givenReviewLearner();
    givenReviewSkills(1L, 2L, 3L);
    // 첫 요소의 후보가 3개뿐이다 — 4개를 뽑을 수 없다
    given(problemRepository.findAssignableByLearningSkillId(1L, 12L))
        .willReturn(List.of(problem(1L, "하나"), problem(2L, "둘"), problem(3L, "셋")));

    assertError(
        () -> problemService.reviewWithNewSession(learner), ErrorCode.LEARNING_CONTENT_NOT_READY);
  }

  /**
   * 후보가 4개여도 그것이 모두 앞 요소에서 이미 뽑힌 문제라면 {@code HashSet}의 크기는 더 늘지 않는다 — 개수만 세는 가드로는 막히지 않는 두 번째 무한
   * 반복이다.
   */
  @Test
  @Timeout(10)
  void 복습_후보가_앞_요소와_완전히_겹치면_거부한다() {
    AuthenticatedUser learner = givenReviewLearner();
    givenReviewSkills(1L, 2L, 3L);
    List<Problem> shared =
        List.of(problem(1L, "하나"), problem(2L, "둘"), problem(3L, "셋"), problem(4L, "넷"));
    given(problemRepository.findAssignableByLearningSkillId(1L, 12L)).willReturn(shared);
    given(problemRepository.findAssignableByLearningSkillId(2L, 12L)).willReturn(shared);

    assertError(
        () -> problemService.reviewWithNewSession(learner), ErrorCode.LEARNING_CONTENT_NOT_READY);
  }

  /** 분석이 요소를 세 개 채워 주지 못하면 {@code learningSkillIds.get(2)}가 {@code IndexOutOfBounds}로 500이 된다. */
  @Test
  @Timeout(10)
  void 복습_분석이_학습_요소를_세_개_주지_못하면_거부한다() {
    AuthenticatedUser learner = givenReviewLearner();
    givenReviewSkills(1L, 2L);

    assertError(
        () -> problemService.reviewWithNewSession(learner), ErrorCode.LEARNING_CONTENT_NOT_READY);
  }

  @Test
  @Timeout(10)
  void 레벨이_없는_학습자도_복습할_수_있다() {
    // users.level은 아무도 설정하지 않아 모든 계정이 NULL이다 (User.changeLevel 호출부 없음).
    // 레벨로 후보를 거르면 `WHERE p.level = NULL`이 되어 한 건도 걸리지 않고, 기록이 충분한
    // 학습자도 "아직 문제가 준비되지 않았어요"만 본다 — 복습이 누구에게도 열리지 않았다
    AuthenticatedUser learner = givenReviewLearner();
    givenReviewSkills(1L, 2L, 3L);
    givenReviewCandidates();

    assertThatNoException().isThrownBy(() -> problemService.reviewWithNewSession(learner));
  }

  @Test
  @Timeout(10)
  void 복습_출제는_요소마다_레벨_없이_후보를_찾는다() {
    // 복습의 목적은 "내가 틀린 것 다시 풀기"다. 레벨로 좁히면 약한 요소의 문항이 그 단계에
    // 없을 때 복습 자체가 불가능해진다
    AuthenticatedUser learner = givenReviewLearner();
    givenReviewSkills(1L, 2L, 3L);
    givenReviewCandidates();

    problemService.reviewWithNewSession(learner);

    verify(problemRepository).findAssignableByLearningSkillId(1L, 12L);
    verify(problemRepository).findAssignableByLearningSkillId(2L, 12L);
    verify(problemRepository).findAssignableByLearningSkillId(3L, 12L);
  }

  @Test
  @Timeout(10)
  void 복습은_학습자의_단계를_읽는다() {
    // 이 값으로 후보를 좁힌다. 읽지 않으면 단어 문항이 후보를 압도해 자기 단계 문항을 만날 수 없다
    AuthenticatedUser learner = givenReviewLearner(Level.CONSONANT);
    givenReviewSkills(1L, 2L, 3L);
    givenPreferredCandidates();

    problemService.reviewWithNewSession(learner);

    verify(curriculumStageRepository).findAll();
  }

  private AuthenticatedUser givenReviewLearner() {
    return givenReviewLearner(null);
  }

  private AuthenticatedUser givenReviewLearner(Level level) {
    long learnerId = 9L;
    User learnerEntity = mock(User.class);
    given(learnerEntity.getLevel()).willReturn(level);
    if (level != null) {
      // 목록을 먼저 만든다. given(...) 인자 안에서 mock 을 스텁하면 Mockito 가 중첩 스텁으로 본다
      List<CurriculumStage> stages = curriculumStages();
      given(curriculumStageRepository.findAll()).willReturn(stages);
    }
    given(userJpaRepository.findById(learnerId)).willReturn(Optional.of(learnerEntity));
    given(
            learningSessionRepository.findAllByUserAndCurriculumStageIsNullAndCompletedAtIsNull(
                learnerEntity))
        .willReturn(List.of());
    given(userSkillMemoryRepository.findAllByUser_Id(learnerId)).willReturn(List.of());
    given(userSkillErrorCountRepository.findAllByUserSkillMemory_IdIn(List.of()))
        .willReturn(List.of());
    return new AuthenticatedUser(learnerId, UserRole.LEARNER);
  }

  /**
   * 요소 세 개가 서로 다른 문제를 4개씩 가져갈 수 있도록 후보 12개를 준다.
   *
   * <p>같은 4개를 모든 요소에 돌려주면 두 번째 요소에서 앞서 뽑힌 것이 모두 걸러져 후보가 0이 된다 — 그건 별도 테스트가 지키는 다른 경우다.
   */
  @Test
  @Timeout(10)
  void 복습은_내_단계_이하_문항을_먼저_쓴다() {
    // 약한 요소의 후보는 단어 문항이 압도한다(예: "첫소리 기본 자음"은 단어 1683개 대 자음 1개).
    // 무작위로만 뽑으면 자음 학습자가 자기 단계 문항을 만날 확률이 1/1684다 — 실제로 복습
    // 12문항이 전부 단어였다
    AuthenticatedUser learner = givenReviewLearner(Level.CONSONANT);
    givenReviewSkills(1L, 2L, 3L);
    givenPreferredCandidates();

    problemService.reviewWithNewSession(learner);

    // 내 단계 이하에서 충분히 나왔으면 넓히지 않는다
    verify(problemRepository, never()).findAssignableByLearningSkillId(anyLong(), anyLong());
  }

  @Test
  @Timeout(10)
  void 내_단계에서_모자라면_위_단계로_채운다() {
    // 자음 문항은 28개뿐이라 요소당 4개를 못 채우는 일이 흔하다. 그때 거부하면 복습이 다시
    // 막힌다 — 모자란 만큼만 넓혀 채운다
    AuthenticatedUser learner = givenReviewLearner(Level.CONSONANT);
    givenReviewSkills(1L, 2L, 3L);
    given(problemRepository.findAssignableByLearningSkillIdAndLevelIn(anyLong(), any(), eq(12L)))
        .willReturn(List.of(problem(1L, "내단계")));
    givenReviewCandidates();

    problemService.reviewWithNewSession(learner);

    verify(problemRepository, atLeastOnce()).findAssignableByLearningSkillId(anyLong(), eq(12L));
  }

  @Test
  @Timeout(10)
  void 단계가_없으면_모든_문항에서_고른다() {
    // users.level은 자가 진단 전이면 NULL이다. 그때 좁히면 후보가 0이 되어 복습이 막힌다
    AuthenticatedUser learner = givenReviewLearner(null);
    givenReviewSkills(1L, 2L, 3L);
    givenReviewCandidates();

    problemService.reviewWithNewSession(learner);

    verify(problemRepository, never())
        .findAssignableByLearningSkillIdAndLevelIn(anyLong(), any(), anyLong());
  }

  @Test
  @Timeout(10)
  void 넓혀도_모자라면_거부한다() {
    AuthenticatedUser learner = givenReviewLearner(Level.CONSONANT);
    givenReviewSkills(1L, 2L, 3L);
    given(problemRepository.findAssignableByLearningSkillIdAndLevelIn(anyLong(), any(), eq(12L)))
        .willReturn(List.of(problem(1L, "내단계")));
    given(problemRepository.findAssignableByLearningSkillId(anyLong(), eq(12L)))
        .willReturn(List.of(problem(1L, "내단계"), problem(2L, "둘")));

    assertError(
        () -> problemService.reviewWithNewSession(learner), ErrorCode.LEARNING_CONTENT_NOT_READY);
  }

  /** 내 단계 이하 후보 12개 — 요소 셋이 서로 다른 4개씩 가져갈 수 있어야 한다 */
  private void givenPreferredCandidates() {
    given(problemRepository.findAssignableByLearningSkillIdAndLevelIn(anyLong(), any(), eq(12L)))
        .willReturn(reviewProblems());
  }

  private void givenReviewCandidates() {
    given(problemRepository.findAssignableByLearningSkillId(anyLong(), eq(12L)))
        .willReturn(reviewProblems());
  }

  private List<Problem> reviewProblems() {
    return List.of(
        problem(1L, "하나"),
        problem(2L, "둘"),
        problem(3L, "셋"),
        problem(4L, "넷"),
        problem(5L, "다섯"),
        problem(6L, "여섯"),
        problem(7L, "일곱"),
        problem(8L, "여덟"),
        problem(9L, "아홉"),
        problem(10L, "열"),
        problem(11L, "열하나"),
        problem(12L, "열둘"));
  }

  /** curriculum_stages 의 실제 순서: 자음·모음·약자·단어·약어·문장 */
  private List<CurriculumStage> curriculumStages() {
    Level[] levels = {
      Level.CONSONANT,
      Level.VOWEL,
      Level.CONTRACTION,
      Level.WORD,
      Level.CONTRACTION_WORD,
      Level.SENTENCE
    };
    List<CurriculumStage> stages = new ArrayList<>();
    for (int i = 0; i < levels.length; i++) {
      CurriculumStage stage = mock(CurriculumStage.class);
      given(stage.getLevel()).willReturn(levels[i]);
      given(stage.getOrderNo()).willReturn(i + 1);
      stages.add(stage);
    }
    return stages;
  }

  private void givenReviewSkills(Long... learningSkillIds) {
    given(fastApiClient.reviewAnalyze(any()))
        .willReturn(new ReviewAnalyzeResponse(List.of(learningSkillIds)));
  }

  private static Problem problem(long id, String targetText) {
    Problem problem = new Problem(Level.WORD, targetText);
    ReflectionTestUtils.setField(problem, "id", id);
    return problem;
  }

  private static UserWrongProblem wrongProblem(User user, Problem problem) {
    return new UserWrongProblem(user, problem, Level.WORD);
  }

  private static final List<Level> BARE_LEVELS =
      List.of(Level.WORD, Level.CONTRACTION_WORD, Level.SENTENCE);
  private static final List<Level> JAMO_LEVELS = List.of(Level.CONSONANT, Level.VOWEL);

  private static Problem problem(Level level, String targetText, long id) {
    Problem problem = new Problem(level, targetText);
    ReflectionTestUtils.setField(problem, "id", id);
    return problem;
  }

  private static LinkedProblem linked(long id, String displayText) {
    return new LinkedProblem(id, "ㄱ", displayText, Level.CONSONANT, List.of(8));
  }

  private Problem savedLinkProblem() {
    verify(lectureProblemRepository).save(lectureProblemCaptor.capture());
    return lectureProblemCaptor.getValue().getProblem();
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

  private static void assertError(ThrowingCallable invocation, ErrorCode expectedErrorCode) {
    assertThatThrownBy(invocation)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(expectedErrorCode));
  }
}
