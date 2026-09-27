package com.itda.problem.dto;

import com.itda.problem.domain.Level;
import java.util.List;

public record ProblemAnalyzeResponse(Level level, List<Integer> cells, List<SkillSet> skillSets) {}
