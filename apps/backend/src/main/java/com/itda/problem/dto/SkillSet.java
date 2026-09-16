package com.itda.problem.dto;

import com.itda.problem.domain.SkillType;

public record SkillSet(
    SkillType skillType,
    String metadata
) {
}
