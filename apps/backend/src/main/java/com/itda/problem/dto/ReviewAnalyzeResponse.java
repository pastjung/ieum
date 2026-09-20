package com.itda.problem.dto;

import com.itda.problem.domain.Level;
import java.util.List;

public record ReviewAnalyzeResponse(Level level, List<Long> learningSkillIds) {}
