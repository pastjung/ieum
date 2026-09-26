package com.itda.problem.service;

import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.SkillType;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemSkillRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 문제 → {@link LinkedProblem}. 문제 등록 응답과 수업 Snapshot이 같은 모양을 쓰므로 한 곳에서 만든다. 셀·학습 요소를 문제 수와 관계없이 두 번의
 * 조회로 읽는다. 호출자의 트랜잭션 안에서 부른다.
 */
@Component
@RequiredArgsConstructor
public class LinkedProblemReader {

  private final ProblemCellRepository problemCellRepository;
  private final ProblemSkillRepository problemSkillRepository;

  public LinkedProblem read(Problem problem) {
    return readAll(List.of(problem)).getFirst();
  }

  /** 받은 순서 그대로 돌려준다. */
  public List<LinkedProblem> readAll(List<Problem> problems) {
    if (problems.isEmpty()) {
      return List.of();
    }
    List<Long> problemIds = problems.stream().map(Problem::getId).distinct().toList();
    Map<Long, List<Integer>> patterns =
        problemCellRepository
            .findAllByProblem_IdInOrderByProblem_IdAscCellIndexAsc(problemIds)
            .stream()
            .collect(
                Collectors.groupingBy(
                    cell -> cell.getProblem().getId(),
                    Collectors.mapping(ProblemCell::getExpectedPattern, Collectors.toList())));
    Map<Long, Set<SkillType>> skillTypes =
        problemSkillRepository.findAllWithSkillByProblemIdIn(problemIds).stream()
            .collect(
                Collectors.groupingBy(
                    link -> link.getProblem().getId(),
                    Collectors.mapping(
                        link -> link.getSkill().getSkillType(), Collectors.toSet())));
    return problems.stream()
        .map(
            problem ->
                new LinkedProblem(
                    problem.getId(),
                    problem.getTargetText(),
                    ProblemDisplay.displayText(
                        problem.getLevel(),
                        problem.getTargetText(),
                        skillTypes.getOrDefault(problem.getId(), Set.of())),
                    problem.getLevel(),
                    List.copyOf(patterns.getOrDefault(problem.getId(), List.of()))))
        .toList();
  }
}
