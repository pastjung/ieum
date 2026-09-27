package com.itda.problem.dto;

import java.util.List;

public record ProblemsResponse(Long sessionId, List<ProblemDetail> problems) {}
