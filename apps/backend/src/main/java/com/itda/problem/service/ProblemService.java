package com.itda.problem.service;

import com.itda.curriculum.domain.CurriculumStage;
import com.itda.curriculum.domain.CurriculumStageRepository;
import com.itda.global.FastApiClient;
import com.itda.global.error.BusinessException;
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

  @Transactional
  public void registerProblem(
      AuthenticatedUser user, Long lectureId, RegisterProblemRequest registerProblemRequest) {
    Lecture lecture = validateInstructor(user, lectureId);
    Problem problem =
        problemRepository
            .findByTargetText(registerProblemRequest.targetText())
            .orElseGet(() -> createProblem(registerProblemRequest));

    lectureProblemRepository.save(new LectureProblem(lecture, problem));

    List<Integer> expectedPatterns =
        problemCellRepository.findAllByProblemOrderByCellIndex(problem).stream()
            .map(ProblemCell::getExpectedPattern)
            .toList();

    ProblemDetail problemDetail =
        new ProblemDetail(
            problem.getId(), 1L, registerProblemRequest.targetText(), expectedPatterns, false);
    // TODO:수강생들에게 문제 전달

  }

  private Problem createProblem(RegisterProblemRequest registerProblemRequest) {
    ProblemAnalyzeResponse analyzeResponse = fastApiClient.analyze(registerProblemRequest);

    validateAnalyzeResponse(analyzeResponse);

    Level level = analyzeResponse.level();
    List<Integer> cellPatterns = analyzeResponse.cells();
    List<SkillSet> skillSets = analyzeResponse.skillSets();

    Problem problem = new Problem(level, registerProblemRequest.targetText());
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
    if (!lecture.isOpen()) {
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

    List<UserWrongProblem> wrongProblems =
        userWrongProblemRepository.findAllByUser_IdAndLevelAndIsCompletedIsNull(
            user.userId(), level);
    int wrongCnt = Math.min(wrongProblems.size(), 4);
    Collections.shuffle(wrongProblems);
    List<Long> wrongProblemIds =
        wrongProblems.stream().limit(wrongCnt).map(wp -> wp.getProblem().getId()).toList();

    List<Problem> problems = problemRepository.findAllByLevelAndIdNotIn(level, wrongProblemIds);

    // 출제할 오답 문제
    List<Problem> selectedWrongProblems =
        wrongProblems.stream().limit(wrongCnt).map(UserWrongProblem::getProblem).toList();

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
              p.getId(),
              (long) i + 1,
              p.getTargetText(),
              cellMap.getOrDefault(p.getId(), List.of()),
              false));
    }

    return new ProblemsResponse(learningSession.getId(), problemDetails);
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

    User curUser =
        userJpaRepository
            .findById(user.userId())
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

    Level level = curUser.getLevel();
    List<Long> learningSkillIds = response.learningSkillIds();

    Set<Long> selectedProblemIds = new HashSet<>();
    ThreadLocalRandom random = ThreadLocalRandom.current();

    for (int i = 1; i <= 3; i++) {
      List<Problem> problem =
          problemRepository.findAllByLevelAndLearningSkillId(level, learningSkillIds.get(i - 1));
      while (selectedProblemIds.size() < i * 4) {
        selectedProblemIds.add(problem.get(random.nextInt(problem.size())).getId());
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
              p.getId(),
              (long) i + 1,
              p.getTargetText(),
              cellMap.getOrDefault(p.getId(), List.of()),
              false));
    }
    return new ProblemsResponse(learningSession.getId(), problemDetails);
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
