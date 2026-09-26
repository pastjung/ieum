package com.itda.problem.service;

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
import com.itda.learning.domain.UserSkillMemory;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureProblem;
import com.itda.lecture.repository.LectureProblemRepository;
import com.itda.lecture.repository.LectureRepository;
import com.itda.problem.domain.*;
import com.itda.problem.dto.*;
import com.itda.problem.repository.*;
import com.itda.problem.service.ProblemTextQuery.Prefix;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.UserJpaRepository;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProblemService {

  /**
   * 한 문항에 허용하는 최대 셀 수.
   *
   * <p>AI의 {@code input_patterns} 상한과 같은 값이다. 여기가 더 크면 풀 수 없는 문제가 출제되고, 더 작으면 풀 수 있는 문제를 버린다. 둘은 함께
   * 움직여야 한다.
   */
  private static final long MAX_ASSIGNABLE_CELLS = 12L;

  /** 복습 세션에서 출제 대상으로 삼는 학습 요소 수. FastAPI가 돌려주는 {@code learningSkillIds}에서 앞에서부터 쓴다. */
  private static final int REVIEW_SKILL_COUNT = 3;

  /** 복습 세션에서 요소 하나당 뽑는 문제 수. 셋을 합쳐 12문항이 된다. */
  private static final int REVIEW_PROBLEMS_PER_SKILL = 4;

  /** 구분어 없는 입력에서 먼저 고르는 단계 — 낱말·약어·문장 (API 명세 8.2.1절). */
  private static final List<Level> BARE_TEXT_LEVELS =
      List.of(Level.WORD, Level.CONTRACTION_WORD, Level.SENTENCE);

  /** 구분어 없는 낱자의 후보 단계. */
  private static final List<Level> JAMO_LEVELS = List.of(Level.CONSONANT, Level.VOWEL);

  private final ProblemRepository problemRepository;
  private final LectureProblemRepository lectureProblemRepository;
  private final ProblemCellRepository problemCellRepository;
  private final ProblemSkillRepository problemSkillRepository;
  private final LectureRepository lectureRepository;
  private final LearningSkillQueryRepository learningSkillQueryRepository;
  private final LearningSessionRepository learningSessionRepository;
  private final CurriculumStageRepository curriculumStageRepository;
  private final ProblemSessionRepository problemSessionRepository;
  private final UserWrongProblemRepository userWrongProblemRepository;
  private final UserSkillMemoryRepository userSkillMemoryRepository;
  private final UserSkillErrorCountRepository userSkillErrorCountRepository;
  private final FastApiClient fastApiClient;
  private final UserJpaRepository userJpaRepository;
  private final LinkedProblemReader linkedProblemReader;

  /**
   * 강사 입력으로 문제를 찾아(없는 낱말은 AI로 만들어) 수업에 연결한다 (API 명세 8.2.1절). 이미 연결된 문제면 저장하지 않는다.
   *
   * <p>동시 요청이 (lecture_id, problem_id) UNIQUE에 걸리면 DataIntegrityViolationException이 나가고 이 트랜잭션은
   * 롤백된다 — 컨트롤러가 다시 불러 기존 연결(200)로 답한다.
   */
  @Transactional
  public RegisteredProblem registerProblem(
      AuthenticatedUser user, Long lectureId, RegisterProblemRequest registerProblemRequest) {
    Lecture lecture = validateInstructor(user, lectureId);
    Problem problem = resolveProblem(ProblemTextQuery.parse(registerProblemRequest.targetText()));
    boolean created =
        lectureProblemRepository
            .findByLecture_IdAndProblem_Id(lecture.getId(), problem.getId())
            .isEmpty();
    if (created) {
      lectureProblemRepository.save(new LectureProblem(lecture, problem));
    }
    return new RegisteredProblem(linkedProblemReader.read(problem), created);
  }

  private Problem resolveProblem(ProblemTextQuery query) {
    String text = query.text();
    if (query.prefix() != null) {
      // 자모·약자는 고정 목록이다. 없으면 AI로 만들지 않고 404로 알린다.
      return firstOrNotFound(prefixedCandidates(query.prefix(), text));
    }
    if (query.isSingleJamo()) {
      return pickJamo(text);
    }
    return problemRepository
        .findAllByTargetTextAndLevelInOrderByIdAsc(text, BARE_TEXT_LEVELS)
        .stream()
        .findFirst()
        // 약자만 있는 글자('억' 등)를 낱말로 또 만들지 않는다 — 같은 글자의 기존 문제를 먼저 쓴다.
        .or(() -> problemRepository.findAllByTargetTextOrderByIdAsc(text).stream().findFirst())
        .orElseGet(() -> createProblem(text));
  }

  private List<Problem> prefixedCandidates(Prefix prefix, String text) {
    return switch (prefix) {
      case ONSET ->
          problemRepository.findAllByTargetTextAndLevelAndSkillType(
              text, Level.CONSONANT, SkillType.SKILL_01);
      case CODA ->
          problemRepository.findAllByTargetTextAndLevelAndSkillType(
              text, Level.CONSONANT, SkillType.SKILL_03);
      case VOWEL ->
          problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc(text, List.of(Level.VOWEL));
      case SYLLABLE ->
          problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc(
              text, List.of(Level.CONTRACTION));
    };
  }

  /** 'ㄱ'처럼 구분어 없는 낱자. 표시 문구가 둘 이상 갈리면 강사에게 고르게 한다 (API 명세 8.2.1절). */
  private Problem pickJamo(String text) {
    List<Problem> candidates =
        problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc(text, JAMO_LEVELS);
    if (candidates.size() <= 1) {
      return firstOrNotFound(candidates);
    }
    // 표시 문구가 같은 중복 문제는 하나로 본다 — 같은 버튼을 두 번 보이지 않는다. id 오름차순이라 작은 id가 남는다.
    List<LinkedProblem> linked = linkedProblemReader.readAll(candidates);
    Map<String, Problem> byDisplayText = new LinkedHashMap<>();
    for (int i = 0; i < candidates.size(); i++) {
      byDisplayText.putIfAbsent(linked.get(i).displayText(), candidates.get(i));
    }
    if (byDisplayText.size() == 1) {
      return byDisplayText.values().iterator().next();
    }
    throw new CandidatesBusinessException(
        ErrorCode.PROBLEM_TEXT_AMBIGUOUS,
        byDisplayText.keySet().stream()
            .map(displayText -> new Candidate(displayText, displayText))
            .toList());
  }

  private static Problem firstOrNotFound(List<Problem> candidates) {
    return candidates.stream()
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorCode.PROBLEM_NOT_FOUND));
  }

  private Problem createProblem(String targetText) {
    ProblemAnalyzeResponse analyzeResponse =
        fastApiClient.analyze(new RegisterProblemRequest(targetText));

    validateAnalyzeResponse(analyzeResponse);

    Level level = analyzeResponse.level();
    List<Integer> cellPatterns = analyzeResponse.cells();
    List<SkillSet> skillSets = analyzeResponse.skillSets();

    Problem problem = new Problem(level, targetText);
    problemRepository.save(problem);

    List<LearningSkill> learningSkills = learningSkillQueryRepository.findAllBySkillSets(skillSets);

    List<ProblemSkill> problemSkills =
        learningSkills.stream().map(skill -> new ProblemSkill(problem, skill)).toList();

    problemSkillRepository.saveAll(problemSkills);

    List<ProblemCell> cells = new ArrayList<>();
    for (int i = 0; i < cellPatterns.size(); i++) {
      cells.add(new ProblemCell(problem, i + 1, cellPatterns.get(i)));
    }

    problemCellRepository.saveAll(cells);

    return problem;
  }

  /** 담당 강사만, SCHEDULED·OPEN에서 연결한다. CLOSED는 409 (API 명세 8.1절). */
  private Lecture validateInstructor(AuthenticatedUser user, Long lectureId) {
    if (!user.role().equals(UserRole.INSTRUCTOR)) {
      throw new BusinessException(ErrorCode.INSTRUCTOR_ACCESS_REQUIRED);
    }

    Lecture lecture =
        lectureRepository
            .findById(lectureId)
            .orElseThrow(() -> new BusinessException(ErrorCode.LECTURE_NOT_FOUND));

    if (!lecture.getInstructorUserId().equals(user.userId())) {
      throw new BusinessException(ErrorCode.LECTURE_NOT_FOUND);
    }
    if (!lecture.isJoinable()) {
      throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
    }

    return lecture;
  }

  private void validateAnalyzeResponse(ProblemAnalyzeResponse analyzeResponse) {
    if (analyzeResponse.level() == null) {
      throw new BusinessException(ErrorCode.PROBLEM_VALIDATION_UNAVAILABLE);
    }
    if (analyzeResponse.skillSets() == null || analyzeResponse.skillSets().isEmpty()) {
      throw new BusinessException(ErrorCode.PROBLEM_VALIDATION_UNAVAILABLE);
    }
    if (analyzeResponse.cells() == null || analyzeResponse.cells().isEmpty()) {
      throw new BusinessException(ErrorCode.PROBLEM_VALIDATION_UNAVAILABLE);
    }
  }

  @Transactional
  public ProblemsResponse learningWithNewSession(
      AuthenticatedUser user, NewStudyRequest newStudyRequest) {
    CurriculumStage curriculumStage =
        curriculumStageRepository
            .findById(newStudyRequest.curriculumId())
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_LEARNING_SESSION_INPUT));
    LearningSession learningSession = createSession(user.userId(), curriculumStage);
    Level level = curriculumStage.getLevel();

    // 오답 재출제도 같은 셀 상한을 거쳐야 한다. 오답 문제는 아래 findAssignableByLevel의 후보에서
    // 제외될 뿐 따로 배정되므로, 여기서 거르지 않으면 13칸 이상 문제가 상한 검사를 통째로 비껴간다.
    List<UserWrongProblem> wrongProblems =
        assignableOnly(
            userWrongProblemRepository.findAllByUser_IdAndLevelAndIsCompletedIsNull(
                user.userId(), level));
    int wrongCnt = Math.min(wrongProblems.size(), 4);
    Collections.shuffle(wrongProblems);

    // 출제할 오답 문제
    List<Problem> selectedWrongProblems =
        wrongProblems.stream().limit(wrongCnt).map(UserWrongProblem::getProblem).toList();

    List<Long> wrongProblemIds = selectedWrongProblems.stream().map(Problem::getId).toList();

    List<Problem> problems =
        problemRepository.findAssignableByLevel(level, wrongProblemIds, MAX_ASSIGNABLE_CELLS);

    int normalCnt = 12 - wrongCnt;

    if (problems.size() < normalCnt) {
      throw new BusinessException(ErrorCode.LEARNING_CONTENT_NOT_READY);
    }

    Set<Integer> selectedIndexes = new HashSet<>();
    ThreadLocalRandom random = ThreadLocalRandom.current();

    while (selectedIndexes.size() < normalCnt) {
      selectedIndexes.add(random.nextInt(problems.size()));
    }

    List<Problem> randomProblems =
        selectedIndexes.stream()
            .map(problems::get)
            .collect(Collectors.toCollection(ArrayList::new));

    randomProblems.addAll(selectedWrongProblems);

    Collections.shuffle(randomProblems);

    List<ProblemSession> problemSessions = new ArrayList<>();

    for (int i = 0; i < randomProblems.size(); i++) {
      Problem problem = randomProblems.get(i);
      problemSessions.add(new ProblemSession(problem, learningSession, (long) i + 1));
    }

    problemSessionRepository.saveAll(problemSessions);

    List<ProblemDetail> problemDetails = new ArrayList<>();

    List<Long> problemIds = randomProblems.stream().map(Problem::getId).toList();

    Map<Long, List<Integer>> cellMap =
        problemCellRepository
            .findAllByProblem_IdInOrderByProblem_IdAscCellIndexAsc(problemIds)
            .stream()
            .collect(
                Collectors.groupingBy(
                    pc -> pc.getProblem().getId(),
                    Collectors.mapping(ProblemCell::getExpectedPattern, Collectors.toList())));
    for (int i = 0; i < randomProblems.size(); i++) {
      Problem p = randomProblems.get(i);
      problemDetails.add(
          new ProblemDetail(
              problemSessions.get(i).getId(),
              p.getId(),
              (long) i + 1,
              p.getTargetText(),
              cellMap.getOrDefault(p.getId(), List.of()),
              false));
    }

    return new ProblemsResponse(learningSession.getId(), problemDetails);
  }

  /**
   * 오답 목록에서 출제 가능한(셀 수가 {@link #MAX_ASSIGNABLE_CELLS} 이하인) 것만 남긴다.
   *
   * <p>셔플 대상이므로 변경 가능한 목록으로 돌려준다.
   */
  private List<UserWrongProblem> assignableOnly(List<UserWrongProblem> wrongProblems) {
    if (wrongProblems.isEmpty()) {
      return new ArrayList<>();
    }
    List<Long> problemIds =
        wrongProblems.stream().map(wp -> wp.getProblem().getId()).distinct().toList();
    Set<Long> assignableIds =
        new HashSet<>(problemRepository.findAssignableIdsIn(problemIds, MAX_ASSIGNABLE_CELLS));
    return wrongProblems.stream()
        .filter(wp -> assignableIds.contains(wp.getProblem().getId()))
        .collect(Collectors.toCollection(ArrayList::new));
  }

  public ProblemsResponse studyWithOldSession(AuthenticatedUser user, Long sessionId) {
    validateLearningSession(user, sessionId);
    List<ProblemSession> selectedSessions =
        problemSessionRepository.findAllBySession_IdOrderBySequence(sessionId);

    List<ProblemDetail> problemDetails = new ArrayList<>();

    List<Long> problemIds = selectedSessions.stream().map(ps -> ps.getProblem().getId()).toList();
    Map<Long, List<Integer>> cellMap =
        problemCellRepository
            .findAllByProblem_IdInOrderByProblem_IdAscCellIndexAsc(problemIds)
            .stream()
            .collect(
                Collectors.groupingBy(
                    pc -> pc.getProblem().getId(),
                    Collectors.mapping(ProblemCell::getExpectedPattern, Collectors.toList())));

    for (int i = 0; i < selectedSessions.size(); i++) {
      ProblemSession s = selectedSessions.get(i);
      Problem p = s.getProblem();
      problemDetails.add(
          new ProblemDetail(
              s.getId(),
              p.getId(),
              s.getSequence(),
              p.getTargetText(),
              cellMap.getOrDefault(p.getId(), List.of()),
              s.isCompleted()));
    }

    return new ProblemsResponse(sessionId, problemDetails);
  }

  private void validateLearningSession(AuthenticatedUser user, Long sessionId) {
    LearningSession ls =
        learningSessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.LEARNING_SESSION_NOT_FOUND));
    if (!ls.getUser().getId().equals(user.userId())) {
      throw new BusinessException(ErrorCode.LEARNING_SESSION_NOT_FOUND);
    }
    if (ls.getCompletedAt() != null) {
      throw new BusinessException(ErrorCode.LEARNING_SESSION_ALREADY_COMPLETED);
    }
  }

  @Transactional
  public ProblemsResponse reviewWithNewSession(AuthenticatedUser user) {

    LearningSession learningSession = createSession(user.userId(), null);

    // fastapi로 사용자의 기록을 보내고 level, learningSkillIds 를 받아오기
    List<UserSkillMemory> userSkillMemories =
        userSkillMemoryRepository.findAllByUser_Id(user.userId());

    List<Long> memoryIds = userSkillMemories.stream().map(UserSkillMemory::getId).toList();

    List<UserSkillErrorCount> errorCounts =
        userSkillErrorCountRepository.findAllByUserSkillMemory_IdIn(memoryIds);

    Map<Long, List<UserSkillErrorCount>> errorCountMap =
        errorCounts.stream().collect(Collectors.groupingBy(e -> e.getUserSkillMemory().getId()));

    List<SkillState> skillStates =
        userSkillMemories.stream()
            .map(
                u -> {
                  List<UserSkillErrorCount> errors =
                      errorCountMap.getOrDefault(u.getId(), List.of());

                  ErrorCount errorCount = ErrorCount.from(errors);

                  return new SkillState(
                      u.getSkill().getId(),
                      u.getTotalSeen(),
                      u.getTotalCorrect(),
                      u.getLastPracticedAt(),
                      u.getMasteryProbability(),
                      errorCount);
                })
            .toList();

    ReviewAnalyzeRequest reviewAnalyzeRequest = new ReviewAnalyzeRequest(skillStates);
    ReviewAnalyzeResponse response = fastApiClient.reviewAnalyze(reviewAnalyzeRequest);

    // 학습자의 단계 이하 문항을 먼저 쓴다. 비어 있으면(자가 진단 전) 좁히지 않는다
    Set<Level> preferredLevels =
        levelsUpTo(
            userJpaRepository
                .findById(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND))
                .getLevel());
    List<Long> learningSkillIds = response.learningSkillIds();
    // 아래 반복이 앞에서부터 REVIEW_SKILL_COUNT개를 꺼내 쓴다. 모자라면 IndexOutOfBounds로 500이
    // 나가므로, 출제할 것이 없다는 사실을 같은 오류로 알린다.
    if (learningSkillIds == null || learningSkillIds.size() < REVIEW_SKILL_COUNT) {
      throw new BusinessException(ErrorCode.LEARNING_CONTENT_NOT_READY);
    }

    Set<Long> selectedProblemIds = new HashSet<>();

    // 요소마다 서로 다른 문제 4개를 뽑는다.
    //
    // 이전에는 HashSet 크기가 i * 4가 될 때까지 무작위 추출을 반복했다. 후보가 4개 미만이거나
    // 이미 고른 것과 겹치면 그 while 문은 절대 끝나지 않는다 — 예외도 나지 않고 요청 스레드가
    // CPU를 태우며 영구히 붙잡힌다. 이 브랜치가 그 조회에 셀 상한을 더해 후보 풀을 좁혔으므로
    // learningWithNewSession과 같은 가드(LEARNING_CONTENT_NOT_READY)가 여기에도 필요하다.
    //
    // 반복 대신 후보를 섞어 앞에서 필요한 만큼 가져간다. 뽑는 결과는 같은 균등 무작위
    // 부분집합이면서 시도 횟수에 상한이 생긴다.
    for (int i = 0; i < REVIEW_SKILL_COUNT; i++) {
      long skillId = learningSkillIds.get(i);
      // 내 단계 이하에서 먼저 채우고, 모자란 만큼만 전체에서 채운다
      List<Long> picked =
          pick(
              candidateIds(
                  preferredLevels.isEmpty()
                      ? List.of()
                      : problemRepository.findAssignableByLearningSkillIdAndLevelIn(
                          skillId, preferredLevels, MAX_ASSIGNABLE_CELLS),
                  selectedProblemIds),
              REVIEW_PROBLEMS_PER_SKILL);
      selectedProblemIds.addAll(picked);

      if (picked.size() < REVIEW_PROBLEMS_PER_SKILL) {
        picked.addAll(
            pick(
                candidateIds(
                    problemRepository.findAssignableByLearningSkillId(
                        skillId, MAX_ASSIGNABLE_CELLS),
                    selectedProblemIds),
                REVIEW_PROBLEMS_PER_SKILL - picked.size()));
        selectedProblemIds.addAll(picked);
      }

      if (picked.size() < REVIEW_PROBLEMS_PER_SKILL) {
        throw new BusinessException(ErrorCode.LEARNING_CONTENT_NOT_READY);
      }
    }

    List<Problem> randomProblems = problemRepository.findAllById(selectedProblemIds);
    Collections.shuffle(randomProblems);

    List<ProblemSession> problemSessions = new ArrayList<>();

    for (int i = 0; i < randomProblems.size(); i++) {
      Problem problem = randomProblems.get(i);

      problemSessions.add(new ProblemSession(problem, learningSession, (long) i + 1));
    }

    problemSessionRepository.saveAll(problemSessions);

    List<ProblemDetail> problemDetails = new ArrayList<>();

    List<Long> problemIds = randomProblems.stream().map(Problem::getId).toList();

    Map<Long, List<Integer>> cellMap =
        problemCellRepository
            .findAllByProblem_IdInOrderByProblem_IdAscCellIndexAsc(problemIds)
            .stream()
            .collect(
                Collectors.groupingBy(
                    pc -> pc.getProblem().getId(),
                    Collectors.mapping(ProblemCell::getExpectedPattern, Collectors.toList())));
    for (int i = 0; i < randomProblems.size(); i++) {
      Problem p = randomProblems.get(i);
      problemDetails.add(
          new ProblemDetail(
              problemSessions.get(i).getId(),
              p.getId(),
              (long) i + 1,
              p.getTargetText(),
              cellMap.getOrDefault(p.getId(), List.of()),
              false));
    }
    return new ProblemsResponse(learningSession.getId(), problemDetails);
  }

  /**
   * 이 단계와 그 아래 단계들. 단계가 없으면(자가 진단 전) 빈 집합 — 좁히지 않는다는 뜻이다.
   *
   * <p>순서는 {@code curriculum_stages.order_no}가 정한다. {@code Level} enum의 선언 순서는 학습 순서와 다르므로 쓰지 않는다.
   */
  private Set<Level> levelsUpTo(Level level) {
    if (level == null) {
      return Set.of();
    }
    List<CurriculumStage> stages = curriculumStageRepository.findAll();
    int myOrder =
        stages.stream()
            .filter(stage -> stage.getLevel() == level)
            .mapToInt(CurriculumStage::getOrderNo)
            .min()
            .orElse(Integer.MAX_VALUE);
    return stages.stream()
        .filter(stage -> stage.getOrderNo() <= myOrder)
        .map(CurriculumStage::getLevel)
        .collect(Collectors.toSet());
  }

  /** 이미 고른 것을 뺀 후보 id */
  private static List<Long> candidateIds(List<Problem> problems, Set<Long> alreadySelected) {
    return problems.stream()
        .map(Problem::getId)
        .filter(id -> !alreadySelected.contains(id))
        .distinct()
        .collect(Collectors.toCollection(ArrayList::new));
  }

  /**
   * 후보를 섞어 앞에서 최대 {@code count}개를 가져간다.
   *
   * <p>예전에는 {@code HashSet} 크기가 목표치가 될 때까지 무작위 추출을 반복했다. 후보가 모자라거나 이미 고른 것과 겹치면 그 반복은 끝나지 않는다 — 예외도
   * 나지 않고 요청 스레드가 CPU를 태우며 영구히 붙잡힌다. 뽑는 결과는 같은 균등 무작위 부분집합이면서 시도 횟수에 상한이 생긴다.
   */
  private static List<Long> pick(List<Long> candidateIds, int count) {
    Collections.shuffle(candidateIds);
    return new ArrayList<>(candidateIds.subList(0, Math.min(count, candidateIds.size())));
  }

  private LearningSession createSession(Long userId, CurriculumStage curriculum) {
    User user =
        userJpaRepository
            .findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    LocalDateTime completedAt = LocalDateTime.now();
    if (curriculum == null) {
      learningSessionRepository
          .findAllByUserAndCurriculumStageIsNullAndCompletedAtIsNull(user)
          .forEach(session -> session.complete(completedAt));
    } else {
      learningSessionRepository
          .findAllByUserAndCurriculumStageIsNotNullAndCompletedAtIsNull(user)
          .forEach(session -> session.complete(completedAt));
    }

    LearningSession learningSession = new LearningSession(user, curriculum);
    learningSessionRepository.save(learningSession);
    return learningSession;
  }
}
