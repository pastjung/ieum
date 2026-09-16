package com.itda.problem.service;

import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureProblem;
import com.itda.lecture.repository.LectureProblemRepository;
import com.itda.lecture.repository.LectureRepository;
import com.itda.problem.domain.*;
import com.itda.problem.dto.ProblemAnalyzeResponse;
import com.itda.problem.dto.RegisterProblemRequest;
import com.itda.problem.dto.SkillSet;
import com.itda.problem.repository.*;
import com.itda.user.domain.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProblemService {
    private final ProblemRepository problemRepository;
    private final LectureProblemRepository lectureProblemRepository;
    private final ProblemCellRepository problemCellRepository;
    private final ProblemSkillRepository problemSkillRepository;
    private final LectureRepository lectureRepository;
    private final LearningSkillQueryRepository learningSkillQueryRepository;
    private final FastApiClient fastApiClient;

    @Transactional
    public void registerProblem(AuthenticatedUser user, Long lectureId, RegisterProblemRequest registerProblemRequest) {
        Lecture lecture = validateInstructor(user, lectureId);
        Long userId = user.userId();

        ProblemAnalyzeResponse analyzeResponse = fastApiClient.analyze(registerProblemRequest);

        validateAnalyzeResponse(analyzeResponse);

        ProblemType problemType = analyzeResponse.problemType();
        List<Integer> cellPatterns = analyzeResponse.cells();
        List<SkillSet> skillSets = analyzeResponse.skillSets();

        Problem problem = new Problem(userId, ProblemSourceType.INSTRUCTOR, problemType,
            registerProblemRequest.targetText());
        problemRepository.save(problem);

        List<LearningSkill> learningSkills = learningSkillQueryRepository.findAllBySkillSets(skillSets);

        List<ProblemSkill> problemSkills = learningSkills
                                            .stream()
                                            .map(skill -> new ProblemSkill(problem, skill))
                                            .toList();

        problemSkillRepository.saveAll(problemSkills);

        lectureProblemRepository.save(new LectureProblem(lecture, problem));

        List<ProblemCell> cells = new ArrayList<>();
        for(int i=0;i<cellPatterns.size();i++){
            cells.add(new ProblemCell(problem, i+1, cellPatterns.get(i)));
        }

        problemCellRepository.saveAll(cells);

        //TODO:수강생들에게 문제 전달
    }

    private Lecture validateInstructor(AuthenticatedUser user, Long lectureId) {
        if(!user.role().equals(UserRole.INSTRUCTOR)) throw new RuntimeException("강사만 문제를 출제할 수 있습니다.");

        Lecture lecture = lectureRepository.findById(lectureId)
            .orElseThrow(()-> new RuntimeException("요청에 해당하는 강의가 없습니다."));

        if(!lecture.getInstructorUserId().equals(user.userId())) throw new RuntimeException("본인 강의에서만 문제를 출제할 수 있습니다");
        if(!lecture.isOpen()) throw new RuntimeException("진행중인 강의에서만 문제를 출제할 수 있습니다.");

        return lecture;
    }

    private void validateAnalyzeResponse(ProblemAnalyzeResponse analyzeResponse) {
        if(analyzeResponse.problemType() == null) throw new RuntimeException("FastAPI 응답의 problemType 이 null 입니다.");
        if(analyzeResponse.skillSets() == null || analyzeResponse.skillSets().isEmpty()) throw new RuntimeException("FastAPI 응답의 skillSets 가 비어 있습니다.");
        if(analyzeResponse.cells() == null || analyzeResponse.cells().isEmpty()) throw new RuntimeException("FastAPI 응답의 cells 가 비어 있습니다.");
    }
}
