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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
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

    List<Integer> expectedPatterns =
        problemCellRepository.findAllByProblem(problem).stream()
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

    lectureProblemRepository.save(new LectureProblem(lecture, problem));

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

    Set<Integer> selectedIndexes = new HashSet<>();
    ThreadLocalRandom random = ThreadLocalRandom.current();

    while (selectedIndexes.size() < 10) {
      selectedIndexes.add(random.nextInt(problems.size()));
    }

    List<Problem> randomProblems = selectedIndexes.stream().map(problems::get).toList();

    //      Long sessionId,
    //      List<org.springframework.http.ProblemDetail> problems

    return null;
  }

  public ProblemsResponse learningWithOldSession(AuthenticatedUser user, Long sessionId) {
    return null;
  }

  public ProblemsResponse reviewWithNewSession(AuthenticatedUser user) {
    return null;
  }

  public ProblemsResponse reviewWithOldSession(AuthenticatedUser user, Long sessionId) {
    return null;
  }

  private LearningSession createSession(Long userId, CurriculumStage curriculum) {
    LearningSession learningSession = new LearningSession(userId, curriculum);
    learningSessionRepository.save(learningSession);
    return learningSession;
  }
}
