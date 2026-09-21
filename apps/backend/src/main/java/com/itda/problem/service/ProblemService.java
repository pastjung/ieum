package com.itda.problem.service;

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
import com.itda.problem.domain.*;
import com.itda.problem.dto.*;
import com.itda.problem.repository.*;
import com.itda.user.domain.UserRole;
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
  private final FastApiClient fastApiClient;

  @Transactional
  public void registerProblem(
      AuthenticatedUser user, Long lectureId, RegisterProblemRequest registerProblemRequest) {
    Lecture lecture = validateInstructor(user, lectureId);
    Problem problem =
        problemRepository
            .findByTargetText(registerProblemRequest.targetText())
            .orElseGet(() -> createProblem(lecture, registerProblemRequest));

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

  private Problem createProblem(Lecture lecture, RegisterProblemRequest registerProblemRequest) {
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
    if (!user.role().equals(UserRole.INSTRUCTOR)) throw new RuntimeException("강사만 문제를 출제할 수 있습니다.");

    Lecture lecture =
        lectureRepository
            .findById(lectureId)
            .orElseThrow(() -> new RuntimeException("요청에 해당하는 강의가 없습니다."));

    if (!lecture.getInstructorUserId().equals(user.userId()))
      throw new RuntimeException("본인 강의에서만 문제를 출제할 수 있습니다");
    if (!lecture.isOpen()) throw new RuntimeException("진행중인 강의에서만 문제를 출제할 수 있습니다.");

    return lecture;
  }

  private void validateAnalyzeResponse(ProblemAnalyzeResponse analyzeResponse) {
    if (analyzeResponse.level() == null)
      throw new RuntimeException("FastAPI 응답의 problemType 이 null 입니다.");
    if (analyzeResponse.skillSets() == null || analyzeResponse.skillSets().isEmpty())
      throw new RuntimeException("FastAPI 응답의 skillSets 가 비어 있습니다.");
    if (analyzeResponse.cells() == null || analyzeResponse.cells().isEmpty())
      throw new RuntimeException("FastAPI 응답의 cells 가 비어 있습니다.");
  }

  @Transactional
  public ProblemsResponse learningWithNewSession(
      AuthenticatedUser user, NewStudyRequest newStudyRequest) {
    CurriculumStage curriculumStage =
        curriculumStageRepository
            .findById(newStudyRequest.curriculumId())
            .orElseThrow(() -> new RuntimeException("해당 커리큘럼이 없습니다."));
    LearningSession learningSession = createSession(user.userId(), curriculumStage);
    Level level = curriculumStage.getLevel();
    List<Problem> problems = problemRepository.findAllByLevel(level);

    if (problems.size() < 12) {
      throw new RuntimeException("출제 가능한 문제가 부족합니다.");
    }

    Set<Integer> selectedIndexes = new HashSet<>();
    ThreadLocalRandom random = ThreadLocalRandom.current();

    while (selectedIndexes.size() < 12) {
      selectedIndexes.add(random.nextInt(problems.size()));
    }

    List<Problem> randomProblems = selectedIndexes.stream().map(problems::get).toList();

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
            .orElseThrow(() -> new RuntimeException("학습 세션이 없습니다."));
    if (!ls.getUserId().equals(user.userId())) {
      throw new RuntimeException("해당 세션의 소유자가 아닙니다.");
    }
  }

  @Transactional
  public ProblemsResponse reviewWithNewSession(AuthenticatedUser user) {

    LearningSession learningSession = createSession(user.userId(), null);

    // fastapi로 사용자의 기록을 보내고 level, learningSkillIds 를 받아오기
    ReviewAnalyzeRequest reviewAnalyzeRequest = new ReviewAnalyzeRequest();
    ReviewAnalyzeResponse response = fastApiClient.reviewAnalyze(reviewAnalyzeRequest);

    Level level = response.level();
    List<Long> learningSkillIds = response.learningSkillIds();

    List<Problem> problems =
        problemRepository.findAllByLevelAndLearningSkillIds(level, learningSkillIds);

    if (problems.size() < 12) {
      throw new RuntimeException("출제 가능한 문제가 부족합니다.");
    }

    Set<Integer> selectedIndexes = new HashSet<>();
    ThreadLocalRandom random = ThreadLocalRandom.current();

    while (selectedIndexes.size() < 12) {
      selectedIndexes.add(random.nextInt(problems.size()));
    }

    List<Problem> randomProblems = selectedIndexes.stream().map(problems::get).toList();

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
    LearningSession learningSession = new LearningSession(userId, curriculum);
    learningSessionRepository.save(learningSession);
    return learningSession;
  }
}
