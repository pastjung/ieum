package com.itda.problem.dto;

import com.itda.problem.domain.ProblemType;

import java.util.List;

public record ProblemAnalyzeResponse(
    ProblemType problemType,
    List<Integer> cells,
    List<SkillSet> skillSets
) {
}
