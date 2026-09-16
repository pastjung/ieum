package com.itda.problem.service;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureProblem;
import com.itda.lecture.repository.LectureProblemRepository;
import com.itda.lecture.repository.LectureRepository;
import com.itda.problem.domain.*;
import com.itda.problem.dto.ProblemAnalyzeResponse;
import com.itda.problem.dto.RegisterProblemRequest;
import com.itda.problem.dto.SkillSet;
import com.itda.problem.repository.*;
import com.itda.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import com.querydsl.core.BooleanBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProblemService {
    private final UserRepository userRepository;
    private final ProblemRepository problemRepository;
    private final LectureProblemRepository lectureProblemRepository;
    private final ProblemCellRepository problemCellRepository;
    private final ProblemSkillRepository problemSkillRepository;
    private final LectureRepository lectureRepository;
    private final LearningSkillRepository learningSkillRepository;
    private final LearningSkillQueryRepository learningSkillQueryRepository;
    private final FastApiClient fastApiClient;

    @Transactional
    public void registerProblem(Long userId, Long lectureId, RegisterProblemRequest registerProblemRequest) {
        validateInstructor(userId, lectureId);

        ProblemAnalyzeResponse analyzeResponse = fastApiClient.analyze(registerProblemRequest);

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

        Lecture lecture = lectureRepository.findById(lectureId)
            .orElseThrow(()-> new RuntimeException("강의가 없습니다."));
        lectureProblemRepository.save(new LectureProblem(lecture, problem));

        List<ProblemCell> cells = new ArrayList<>();
        for(int i=0;i<cellPatterns.size();i++){
            cells.add(new ProblemCell(problem, i+1, cellPatterns.get(i)));
        }

        problemCellRepository.saveAll(cells);

        //TODO:수강생들에게 문제 전달
    }



    private void validateInstructor(Long userId, Long lectureId){
        Lecture lecture = lectureRepository.findById(lectureId).
            orElseThrow(()-> new RuntimeException("해당 강의가 없습니다."));
        if(!lecture.getInstructorUserId().equals(userId)){
            throw new RuntimeException("해당 강의의 강사가 아닙니다.");
        }
        if(!lecture.isOpen()){
            throw new RuntimeException("진행중인 강의가 아닙니다.");
        }
    }
}
